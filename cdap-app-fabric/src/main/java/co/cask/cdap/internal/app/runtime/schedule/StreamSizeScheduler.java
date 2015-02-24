/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.schedule;

import co.cask.cdap.api.schedule.SchedulableProgramType;
import co.cask.cdap.api.schedule.Schedule;
import co.cask.cdap.app.runtime.Arguments;
import co.cask.cdap.app.runtime.ProgramRuntimeService;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.app.store.StoreFactory;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.stream.notification.StreamSizeNotification;
import co.cask.cdap.config.PreferencesStore;
import co.cask.cdap.data2.transaction.stream.StreamAdmin;
import co.cask.cdap.internal.app.runtime.BasicArguments;
import co.cask.cdap.internal.app.runtime.ProgramOptionConstants;
import co.cask.cdap.internal.schedule.StreamSizeSchedule;
import co.cask.cdap.notifications.feeds.NotificationFeedException;
import co.cask.cdap.notifications.feeds.NotificationFeedNotFoundException;
import co.cask.cdap.notifications.service.NotificationContext;
import co.cask.cdap.notifications.service.NotificationHandler;
import co.cask.cdap.notifications.service.NotificationService;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramType;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.twill.common.Cancellable;
import org.apache.twill.common.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link Scheduler} that triggers program executions based on data availability in streams.
 */
@Singleton
public class StreamSizeScheduler implements Scheduler {
  private static final Logger LOG = LoggerFactory.getLogger(StreamSizeScheduler.class);
  private static final int STREAM_POLLING_THREAD_POOL_SIZE = 10;

  private final long pollingDelay;
  private final NotificationService notificationService;
  private final StreamAdmin streamAdmin;
  private final StoreFactory storeFactory;
  private final ProgramRuntimeService programRuntimeService;
  private final PreferencesStore preferencesStore;
  private final ConcurrentMap<Id.Stream, StreamSubscriber> streamSubscribers;

  // Key is scheduleId
  private final ConcurrentSkipListMap<String, StreamSubscriber> scheduleSubscribers;

  private Store store;
  private Executor notificationExecutor;
  private ScheduledExecutorService streamPollingExecutor;

  @Inject
  public StreamSizeScheduler(CConfiguration cConf, NotificationService notificationService, StreamAdmin streamAdmin,
                             StoreFactory storeFactory, ProgramRuntimeService programRuntimeService,
                             PreferencesStore preferencesStore) {
    this.pollingDelay = TimeUnit.SECONDS.toMillis(
      cConf.getLong(Constants.Notification.Stream.STREAM_SIZE_SCHEDULE_POLLING_DELAY));
    this.notificationService = notificationService;
    this.streamAdmin = streamAdmin;
    this.storeFactory = storeFactory;
    this.programRuntimeService = programRuntimeService;
    this.preferencesStore = preferencesStore;
    this.streamSubscribers = Maps.newConcurrentMap();
    this.scheduleSubscribers = new ConcurrentSkipListMap<String, StreamSubscriber>();
    this.store = null;
  }

  public void start() {
    notificationExecutor = Executors.newCachedThreadPool(Threads.createDaemonThreadFactory("stream-size-scheduler-%d"));
    streamPollingExecutor = Executors.newScheduledThreadPool(STREAM_POLLING_THREAD_POOL_SIZE,
                                                             Threads.createDaemonThreadFactory("stream-polling-%d"));
  }

  public void stop() {
    streamPollingExecutor.shutdownNow();
    for (StreamSubscriber subscriber : streamSubscribers.values()) {
      subscriber.cancel();
    }
  }

  @Override
  public void schedule(Id.Program program, SchedulableProgramType programType, Schedule schedule) {
    Preconditions.checkArgument(schedule instanceof StreamSizeSchedule,
                                "Schedule should be of type StreamSizeSchedule");
    StreamSizeSchedule streamSizeSchedule = (StreamSizeSchedule) schedule;
    schedule(program, programType, streamSizeSchedule, true, -1, -1, true);
  }

  private void schedule(Id.Program program, SchedulableProgramType programType, StreamSizeSchedule streamSizeSchedule,
                        boolean active, long baseRunSize, long baseRunTs, boolean persist) {
    // Create a new StreamSubscriber, if one doesn't exist for the stream passed in the schedule
    Id.Stream streamId = Id.Stream.from(program.getNamespaceId(), streamSizeSchedule.getStreamName());
    StreamSubscriber streamSubscriber = new StreamSubscriber(streamId);
    synchronized (this) {
      // This block is synchronized so that we can't have the following situation:
      // - creation of a schedule, using an existing StreamSubscriber which has one other schedule
      // - deletion of the other schedule, leading to deletion of existing StreamSubscriber
      // - add the first schedule to the StreamSubscriber, which has been removed from streamSubscribers

      StreamSubscriber previous = streamSubscribers.putIfAbsent(streamId, streamSubscriber);
      if (previous == null) {
        try {
          streamSubscriber.start();
        } catch (NotificationFeedException e) {
          streamSubscribers.remove(streamId);
          LOG.error("Notification feed error for streamSizeSchedule {}", streamSizeSchedule);
          throw Throwables.propagate(e);
        } catch (NotificationFeedNotFoundException e) {
          streamSubscribers.remove(streamId);
          LOG.error("Notification feed does not exist for streamSizeSchedule {}", streamSizeSchedule);
          throw Throwables.propagate(e);
        }
      } else {
        streamSubscriber = previous;
      }

      // Add the scheduleTask to the StreamSubscriber
      if (streamSubscriber.createScheduleTask(program, programType, streamSizeSchedule,
                                          active, baseRunSize, baseRunTs, persist)) {
        scheduleSubscribers.put(getScheduleId(program, programType, streamSizeSchedule.getName()), streamSubscriber);
      }
    }
  }

  @Override
  public void schedule(Id.Program program, SchedulableProgramType programType, Iterable<Schedule> schedules) {
    for (Schedule s : schedules) {
      schedule(program, programType, s);
    }
  }

  @Override
  public List<ScheduledRuntime> nextScheduledRuntime(Id.Program program, SchedulableProgramType programType) {
    return ImmutableList.of();
  }

  @Override
  public List<String> getScheduleIds(Id.Program program, SchedulableProgramType programType) {
    char startChar = ':';
    char endChar = (char) (startChar + 1);
    String programScheduleId = getProgramScheduleId(program, programType);
    return ImmutableList.copyOf(scheduleSubscribers.subMap(String.format("%s%c", programScheduleId, startChar),
                                                           String.format("%s%c", programScheduleId, endChar))
                                  .keySet());
  }

  @Override
  public void suspendSchedule(Id.Program program, SchedulableProgramType programType, String scheduleName) {
    try {
      String scheduleId = getScheduleId(program, programType, scheduleName);
      StreamSubscriber subscriber = scheduleSubscribers.get(scheduleId);
      if (subscriber == null) {
        throw new IllegalArgumentException("Schedule not found: " + scheduleId);
      }
      subscriber.suspendScheduleTask(program, programType, scheduleName);
    } catch (ScheduleNotFoundException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void resumeSchedule(Id.Program program, SchedulableProgramType programType, String scheduleName) {
    try {
      String scheduleId = getScheduleId(program, programType, scheduleName);
      StreamSubscriber subscriber = scheduleSubscribers.get(scheduleId);
      if (subscriber == null) {
        throw new IllegalArgumentException("Schedule not found: " + scheduleId);
      }
      subscriber.resumeScheduleTask(program, programType, scheduleName);
    } catch (ScheduleNotFoundException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void deleteSchedule(Id.Program programId, SchedulableProgramType programType, String scheduleName) {
    try {
      String scheduleId = getScheduleId(programId, programType, scheduleName);
      StreamSubscriber subscriber = scheduleSubscribers.remove(scheduleId);
      if (subscriber == null) {
        throw new IllegalArgumentException("Schedule not found: " + scheduleId);
      }
      subscriber.deleteSchedule(programId, programType, scheduleName);
      synchronized (this) {
        if (subscriber.isEmpty()) {
          subscriber.cancel();
          Id.Stream streamId = subscriber.getStreamId();
          streamSubscribers.remove(streamId);
        }
      }
    } catch (ScheduleNotFoundException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void deleteSchedules(Id.Program programId, SchedulableProgramType programType) {
    char startChar = ':';
    char endChar = (char) (startChar + 1);
    String programScheduleId = getProgramScheduleId(programId, programType);
    NavigableSet<String> scheduleIds = scheduleSubscribers.subMap(String.format("%s%c", programScheduleId, startChar),
                                                                  String.format("%s%c", programScheduleId, endChar))
      .keySet();
    int scheduleIdIdx = programScheduleId.length() + 1;
    for (String scheduleId : scheduleIds) {
      if (scheduleId.length() < scheduleIdIdx) {
        LOG.warn("Format of scheduleID incorrect: {}", scheduleId);
        continue;
      }
      deleteSchedule(programId, programType, scheduleId.substring(scheduleIdIdx));
    }
  }

  @Override
  public ScheduleState scheduleState(Id.Program program, SchedulableProgramType programType, String scheduleName) {
    StreamSubscriber subscriber = scheduleSubscribers.get(getScheduleId(program, programType, scheduleName));
    if (subscriber != null) {
      return subscriber.scheduleTaskState(program, programType, scheduleName);
    } else {
      return ScheduleState.NOT_FOUND;
    }
  }

  private synchronized Store getStore() {
    if (store == null) {
      store = storeFactory.create();
    }
    return store;
  }

  private String getScheduleId(Id.Program program, SchedulableProgramType programType, String scheduleName) {
    return String.format("%s:%s", getProgramScheduleId(program, programType), scheduleName);
  }

  private String getProgramScheduleId(Id.Program program, SchedulableProgramType programType) {
    return String.format("%s:%s:%s:%s", program.getNamespaceId(), program.getApplicationId(),
                         programType.name(), program.getId());
  }

  /**
   * One instance of this class contains a list of {@link StreamSizeSchedule}s, which are all interested
   * in the same stream. This instance subscribes to the size notification of the stream, and polls the
   * stream for its size whenever the schedules it references need the information.
   * The {@link StreamSizeScheduler} communicates with this class, which in turn communicates to the schedules
   * it contains to perform operations on the schedules - suspend, resume, etc.
   */
  private final class StreamSubscriber implements NotificationHandler<StreamSizeNotification>, Cancellable {
    // Key is the schedule ID
    private final ConcurrentMap<String, StreamSizeScheduleTask> scheduleTasks;
    private final Object lastNotificationLock;
    private final Id.Stream streamId;
    private final AtomicInteger activeTasks;

    private Cancellable notificationSubscription;
    private ScheduledFuture<?> scheduledPolling;
    private StreamSizeNotification lastNotification;

    private StreamSubscriber(Id.Stream streamId) {
      this.streamId = streamId;
      this.scheduleTasks = Maps.newConcurrentMap();
      this.lastNotificationLock = new Object();
      this.activeTasks = new AtomicInteger(0);
    }

    public void start() throws NotificationFeedException, NotificationFeedNotFoundException {
      notificationSubscription = notificationService.subscribe(getFeed(), this, notificationExecutor);
    }

    @Override
    public void cancel() {
      if (scheduledPolling != null) {
        scheduledPolling.cancel(true);
      }
      if (notificationSubscription != null) {
        notificationSubscription.cancel();
      }
    }

    /**
     * Add a new scheduling task based on the data received by the stream referenced by {@code this} object.
     * @return {@code true} if the task was created successfully, {@code false} if it already exists
     */
    public boolean createScheduleTask(Id.Program programId, SchedulableProgramType programType,
                                      StreamSizeSchedule streamSizeSchedule, boolean active,
                                      long baseRunSize, long baseRunTs, boolean persist) {
      // TODO add a createScheduleTasks, so that if we create multiple schedules for the same stream at the same
      // time, we don't have to poll the stream many times

      StreamSizeScheduleTask newTask = new StreamSizeScheduleTask(programId, programType, streamSizeSchedule);
      synchronized (this) {
        StreamSizeScheduleTask previous =
          scheduleTasks.putIfAbsent(getScheduleId(programId, programType, streamSizeSchedule.getName()), newTask);
        if (previous != null) {
          // We cannot replace an existing schedule - that functionality is not wanted - yet
          return false;
        }

        if (active) {
          activeTasks.incrementAndGet();
        }
      }

      // Initialize the schedule task
      if (baseRunSize == -1 && baseRunTs == -1) {
        // This is the first time that we schedule this task - ie it was not in the schedule store
        // before. Hence we set the base metrics properly
        StreamSize streamSize = pollStream();
        newTask.startSchedule(streamSize.getSize(), streamSize.getTimestamp(), active, persist);
        synchronized (lastNotificationLock) {
          lastNotification = new StreamSizeNotification(streamSize.getTimestamp(), streamSize.getSize());
        }
      } else {
        newTask.startSchedule(baseRunSize, baseRunTs, active, persist);
      }

      if (lastNotification != null) {
        // In all cases, when creating a schedule - either if it comes from the store or not,
        // we want to pass it the last seen notification if it exists.
        // This call will send the notification to all the active tasks. The ones which are active before
        // that method is called will therefore see the notification twice. It is fine though.
        received(lastNotification, null);
      }
      return true;
    }

    /**
     * Suspend a scheduling task that is based on the data received by the stream referenced by {@code this} object.
     */
    public synchronized void suspendScheduleTask(Id.Program programId, SchedulableProgramType programType,
                                                 String scheduleName) throws ScheduleNotFoundException {
      String scheduleId = getScheduleId(programId, programType, scheduleName);
      StreamSizeScheduleTask task = scheduleTasks.get(scheduleId);
      if (task == null) {
        throw new ScheduleNotFoundException(scheduleId);
      }
      if (task.suspend()) {
        activeTasks.decrementAndGet();
      }
    }

    /**
     * Resume a scheduling task that is based on the data received by the stream referenced by {@code this} object.
     */
    public void resumeScheduleTask(Id.Program programId, SchedulableProgramType programType, String scheduleName)
      throws ScheduleNotFoundException{
      int activeTasksNow;
      StreamSizeScheduleTask task;
      synchronized (this) {
        String scheduleId = getScheduleId(programId, programType, scheduleName);
        task = scheduleTasks.get(scheduleId);
        if (task == null) {
          throw new ScheduleNotFoundException(scheduleId);
        }
        if (!task.resume()) {
          return;
        }
        activeTasksNow = activeTasks.incrementAndGet();
      }
      if (activeTasksNow == 1) {
        // There were no active tasks until then, that means polling the stream was disabled.
        // We need to check if it is necessary to poll the stream at this time, if the last
        // notification received was too long ago, or if there is no last seen notification
        synchronized (lastNotificationLock) {
          if (lastNotification == null ||
            (lastNotification.getTimestamp() + pollingDelay <= System.currentTimeMillis())) {
            StreamSize streamSize = pollStream();
            lastNotification = new StreamSizeNotification(streamSize.getTimestamp(), streamSize.getSize());
          }
        }
      }
      task.received(lastNotification);
    }

    /**
     * Delete a scheduling task that is based on the data received by the stream referenced by {@code this} object.
     */
    public synchronized void deleteSchedule(Id.Program programId, SchedulableProgramType programType,
                                            String scheduleName) throws ScheduleNotFoundException {
      String scheduleId = getScheduleId(programId, programType, scheduleName);
      StreamSizeScheduleTask scheduleTask = scheduleTasks.remove(scheduleId);
      if (scheduleTask == null) {
        throw new ScheduleNotFoundException(scheduleId);
      }
      if (scheduleTask.isActive()) {
        activeTasks.decrementAndGet();
      }
    }

    /**
     * Get the status a scheduling task that is based on the data received by the stream referenced by {@code this}
     * object.
     */
    public ScheduleState scheduleTaskState(Id.Program programId, SchedulableProgramType programType,
                                           String scheduleName) {
      StreamSizeScheduleTask task = scheduleTasks.get(getScheduleId(programId, programType, scheduleName));
      if (task == null) {
        return ScheduleState.NOT_FOUND;
      }
      return task.isActive() ? ScheduleState.SCHEDULED : ScheduleState.SUSPENDED;
    }

    /**
     * @return true if this object does not reference any schedule, false otherwise
     */
    public boolean isEmpty() {
      return scheduleTasks.isEmpty();
    }

    public Id.Stream getStreamId() {
      return streamId;
    }

    @Override
    public Type getNotificationFeedType() {
      return StreamSizeNotification.class;
    }

    @Override
    public void received(StreamSizeNotification notification, NotificationContext notificationContext) {
      // We only pass the stream size notification to the schedule tasks if the notification
      // came after the last seen notification
      boolean send = false;
      synchronized (lastNotificationLock) {
        if (lastNotification == null || notification.getTimestamp() > lastNotification.getTimestamp()) {
          send = true;
          lastNotification = notification;
        }
      }
      if (send) {
        sendNotificationToActiveTasks(notification);
        cancelPollingAndScheduleNext();
      }
    }

    /**
     * Send a {@link StreamSizeNotification} to all the active {@link StreamSizeSchedule} referenced
     * by this object.
     */
    private void sendNotificationToActiveTasks(final StreamSizeNotification notification) {
      for (final StreamSizeScheduleTask task : scheduleTasks.values()) {
        if (!task.isActive()) {
          continue;
        }
        notificationExecutor.execute(new Runnable() {
          @Override
          public void run() {
            task.received(notification);
          }
        });
      }
    }

    private Id.NotificationFeed getFeed() {
      return new Id.NotificationFeed.Builder()
        .setNamespaceId(streamId.getNamespaceId())
        .setCategory(Constants.Notification.Stream.STREAM_FEED_CATEGORY)
        .setName(String.format("%sSize", streamId.getName()))
        .build();
    }

    /**
     * Cancel the currently scheduled stream size polling task, and reschedule one for later.
     */
    private void cancelPollingAndScheduleNext() {
      // This method might be called from the call to #received defined in the below Runnable - in which case
      // this scheduledPolling would in fact be active. Hence we don't want to interrupt the active task
      if (scheduledPolling != null) {
        scheduledPolling.cancel(false);
      }

      // Regardless of whether cancelling was successful, we still want to schedule the next polling
      scheduledPolling = streamPollingExecutor.schedule(createPollingRunnable(), pollingDelay, TimeUnit.MILLISECONDS);
    }

    /**
     * @return a runnable that uses the {@link StreamAdmin} to poll the stream size, and creates a fake notification
     *         with that size, so that this information can be treated as if it came from a real notification.
     */
    private Runnable createPollingRunnable() {
      return new Runnable() {
        @Override
        public void run() {
          // We only perform polling if at least one scheduleTask is active
          if (activeTasks.get() > 0) {
            StreamSize streamSize = pollStream();

            // We don't need a notification context here
            received(new StreamSizeNotification(streamSize.getTimestamp(), streamSize.getSize()), null);
          }
        }
      };
    }

    /**
     * @return size of the stream queried directly from the file system
     */
    private StreamSize pollStream() {
      try {
        // Note we can't store the stream config, because its generation might change at every moment
        long size = streamAdmin.fetchStreamSize(streamAdmin.getConfig(streamId));
        return new StreamSize(size, System.currentTimeMillis());
      } catch (IOException e) {
        LOG.error("Could not poll size for stream {}", streamId);
        throw Throwables.propagate(e);
      }
    }
  }

  /**
   * Wrapper around a {@link StreamSizeSchedule} which will run a program whenever it receives enough
   * data from a stream, via notifications.
   */
  private final class StreamSizeScheduleTask {
    private final Id.Program programId;
    private final SchedulableProgramType programType;
    private final StreamSizeSchedule streamSizeSchedule;

    private long baseSize;
    private long baseTs;
    private AtomicBoolean active;

    private StreamSizeScheduleTask(Id.Program programId, SchedulableProgramType programType,
                                   StreamSizeSchedule streamSizeSchedule) {
      this.programId = programId;
      this.programType = programType;
      this.streamSizeSchedule = streamSizeSchedule;
    }

    public void startSchedule(long baseSize, long baseTs, boolean active, boolean persist) {
      LOG.debug("Starting schedule {} with baseSize {}, baseTs {}, active {}. Should be persisted: {}",
                streamSizeSchedule.getName(), baseSize, baseTs, active, persist);
      this.baseSize = baseSize;
      this.baseTs = baseTs;
      this.active = new AtomicBoolean(active);
    }

    public boolean isActive() {
      return active.get();
    }

    public void received(StreamSizeNotification notification) {
      if (!active.get()) {
        return;
      }
      long pastRunSize;
      long pastRunTs;
      synchronized (this) {
        if (notification.getSize() < baseSize) {
          // This can happen when a stream is truncated: the baseSize is still the old size,
          // but we receive notification with way less data
          baseSize = notification.getSize();
          baseTs = notification.getTimestamp();
          return;
        }
        if (notification.getSize() < baseSize + toBytes(streamSizeSchedule.getDataTriggerMB())) {
          return;
        }

        // Update the baseSize as soon as possible to avoid races
        pastRunSize = baseSize;
        pastRunTs = baseTs;
        baseSize = notification.getSize();
        baseTs = notification.getTimestamp();
        LOG.debug("Base size and ts updated to {}, {} for streamSizeSchedule {}",
                  baseSize, baseTs, streamSizeSchedule);
      }

      Arguments args = new BasicArguments(ImmutableMap.of(
        ProgramOptionConstants.SCHEDULE_NAME, streamSizeSchedule.getName(),
        ProgramOptionConstants.LOGICAL_START_TIME, Long.toString(baseTs),
        ProgramOptionConstants.RUN_DATA_SIZE, Long.toString(baseSize),
        ProgramOptionConstants.PAST_RUN_LOGICAL_START_TIME, Long.toString(pastRunTs),
        ProgramOptionConstants.PAST_RUN_DATA_SIZE, Long.toString(pastRunSize)
      ));

      while (true) {
        ScheduleTaskRunner taskRunner = new ScheduleTaskRunner(getStore(), programRuntimeService, preferencesStore);
        try {
          LOG.info("About to start streamSizeSchedule {}", streamSizeSchedule);
          taskRunner.run(programId, ProgramType.valueOf(programType.name()), args);
          break;
        } catch (TaskExecutionException e) {
          LOG.error("Execution exception while running streamSizeSchedule {}", streamSizeSchedule.getName(), e);
          if (e.isRefireImmediately()) {
            LOG.info("Retrying execution for streamSizeSchedule {}", streamSizeSchedule.getName());
          } else {
            break;
          }
        }
      }
    }

    /**
     * @return true if we successfully suspended the schedule, false if it was already suspended
     */
    public boolean suspend() {
      return active.compareAndSet(true, false);
    }

    /**
     * @return true if we successfully resumed the schedule, false if it was already active
     */
    public boolean resume() {
      return active.compareAndSet(false, true);
    }

    private long toBytes(int mb) {
      return ((long) mb) * 1024 * 1024;
    }
  }

  /**
   * Class representing the size of data present in a stream at a given time.
   */
  private final class StreamSize {
    private final long size;
    private final long timestamp;

    private StreamSize(long size, long timestamp) {
      this.size = size;
      this.timestamp = timestamp;
    }

    public long getSize() {
      return size;
    }

    public long getTimestamp() {
      return timestamp;
    }
  }
}
