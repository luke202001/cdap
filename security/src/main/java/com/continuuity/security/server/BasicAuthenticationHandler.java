package com.continuuity.security.server;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.google.inject.Inject;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.util.security.Constraint;

/**
 * Handler for basic authentication of users.
 */
public class BasicAuthenticationHandler extends ConstraintSecurityHandler {

  @Inject
  public BasicAuthenticationHandler(CConfiguration configuration) throws Exception {
    super();

    String[] roles = Constants.Security.BASIC_USER_ROLES;
    Constraint constraint = new Constraint();
    constraint.setName(Constraint.__BASIC_AUTH);
    constraint.setRoles(roles);
    constraint.setAuthenticate(true);

    ConstraintMapping constraintMapping = new ConstraintMapping();
    constraintMapping.setConstraint(constraint);
    constraintMapping.setPathSpec("/*");

    String realmFile = configuration.get(Constants.Security.BASIC_REALM_FILE);
    HashLoginService loginService = new HashLoginService();
    loginService.setConfig(realmFile);
    loginService.loadUsers();
    this.setAuthenticator(new BasicAuthenticator());
    this.setLoginService(loginService);
    this.setConstraintMappings(new ConstraintMapping[]{constraintMapping});
  }
}
