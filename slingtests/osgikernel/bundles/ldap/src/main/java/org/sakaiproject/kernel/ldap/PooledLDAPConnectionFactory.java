/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.kernel.ldap;

import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPConstraints;
import com.novell.ldap.LDAPException;

import org.apache.commons.pool.PoolableObjectFactory;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.sakaiproject.kernel.api.ldap.LdapConnectionLivenessValidator;
import org.sakaiproject.kernel.api.ldap.LdapConnectionManager;
import org.sakaiproject.kernel.api.ldap.LdapConnectionManagerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

/**
 * An object factory for managing <code>PooledLDAPConnection<code>s
 * within the commons-pool library.  Handles creating, configuring,
 * connecting, securing, and binding the connections as they pass
 * in and out of the pool.
 *
 * @see LdapConnectionManager
 * @see LdapConnectionManagerConfig
 * @author John Lewis, Unicon Inc
 */
public class PooledLDAPConnectionFactory implements PoolableObjectFactory {

  /** Class-specific logger */
  private static Logger log = LoggerFactory.getLogger(PooledLDAPConnectionFactory.class);

  /** the controlling connection manager */
  private LdapConnectionManager connectionManager;

  /** connection information */
  private String host;
  private int port;
  private String binddn;
  private byte[] bindpw;
  private boolean autoBind;

  /** flag to tell us if we are using TLS */
  private boolean useTLS;

  /** standard set of LDAP constraints */
  private LDAPConstraints standardConstraints;

  @Reference(cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC, bind = "bindLivenessValidator", unbind = "unbindLivenessValidator")
  private List<LdapConnectionLivenessValidator> livenessValidators = new LinkedList<LdapConnectionLivenessValidator>();

  protected void bindLivenessValidator(LdapConnectionLivenessValidator validator) {
    livenessValidators.add(validator);
  }

  protected void unbindLivenessValidator(LdapConnectionLivenessValidator validator) {
    livenessValidators.remove(validator);
  }

  /**
   * Constructs a new PooledLDAPConnection object, including: passing it the
   * connection manager so it can return itself to the pool if it falls out of
   * scope, setting a default set of constraints, connecting to the server,
   * initiating TLS if needed. Lastly it optionally binds as the "auto-bind
   * user" and clears the bindAttempted flag so we will know if a user of the
   * connection attempts to rebind it.
   */
  public Object makeObject() throws LDAPException {
    log.debug("makeObject()");
    PooledLDAPConnection conn = newConnection();

    log.debug("makeObject(): instantiated connection");
    conn.setConnectionManager(connectionManager);

    log.debug("makeObject(): assigned connection ConnectionManager");
    conn.setConstraints(standardConstraints);

    log.debug("makeObject(): assigned connection constraints");
    conn.connect(host, port);

    log.debug("makeObject(): connected connection");
    if (useTLS) {
      log.debug("makeObject(): attempting to initiate TLS");
      conn.startTLS();
      log.debug("makeObject(): successfully initiated TLS");
    }
    if (this.autoBind) {
      log.debug("makeObject(): binding connection to default bind DN [{}]", binddn);
      conn.bind(LDAPConnection.LDAP_V3, binddn, bindpw);
      log.debug("makeObject(): successfully bound connection to default bind DN []", binddn);
    }
    conn.setBindAttempted(false);
    log.debug("makeObject(): reset connection bindAttempted flag");
    return conn;
  }

  protected PooledLDAPConnection newConnection() {
    return new PooledLDAPConnection();
  }

  /**
   * Activates a PooledLDAPConnection as it is being loaned out from the pool,
   * including: ensuring the default constraints are set and setting the active
   * flag so that it can return itself to the pool if it falls out of scope.
   */
  public void activateObject(Object obj) throws LDAPException {
    log.debug("activateObject()");
    if (obj instanceof PooledLDAPConnection) {
      PooledLDAPConnection conn = (PooledLDAPConnection) obj;
      conn.setConstraints(standardConstraints);
      log.debug("activateObject(): assigned connection constraints");
      conn.setActive(true);
      log.debug("activateObject(): set connection active flag");
    } else {
      log.debug("activateObject(): connection not of expected type [{}] nothing to do",
          (obj == null ? "null" : obj.getClass().getName()));
    }
  }

  /**
   * Passivates a PooledLDAPConnection as it is being returned to the pool,
   * including: clearing the active flag so that it won't attempt to return
   * itself to the pool.
   */
  public void passivateObject(Object obj) throws LDAPException {
    log.debug("passivateObject()");
    if (obj instanceof PooledLDAPConnection) {
      PooledLDAPConnection conn = (PooledLDAPConnection) obj;
      conn.setActive(false);
      log.debug("passivateObject(): unset connection active flag");
    } else {
      log.debug("passivateObject(): connection not of expected type [{}] nothing to do",
          (obj == null ? "null" : obj.getClass().getName()));
    }
  }

  /**
   * Validates a PooledLDAPConnection by checking if the connection is alive and
   * ensuring it is properly bound as the autoBind user. If a borrower attempted
   * to rebind the connection, then the bindAttempted flag will be true -- in
   * that case rebind it as the autoBind user and clear the bindAttempted flag.
   */
  public boolean validateObject(Object obj) {
    log.debug("validateObject()");
    if (obj == null) {
      log.debug("validateObject(): received null object reference, returning false");
      return false;
    }
    if (obj instanceof PooledLDAPConnection) {
      PooledLDAPConnection conn = (PooledLDAPConnection) obj;
      log.debug("validateObject(): received PooledLDAPConnection object to validate");

      // ensure we're always bound as the system user so the liveness
      // search can succeed (it actually uses the system user's account as
      // the base DN)
      if (conn.isBindAttempted()) {

        log.debug("validateObject(): connection bindAttempted flag is set");

        if (!(autoBind)) {
          log
              .debug("validateObject(): last borrower attempted bind operation, but no default bind credentials available, invalidating connection");
          conn.setActive(false);
          log
              .debug("validateObject(): unset connection bindAttempted flag due to missing default bind credentials, returning false");
          return false;
        }

        try {
          log
              .debug(
                  "validateObject(): last borrower attempted bind operation - rebinding with defaults [bind dn: {}]",
                  binddn);
          conn.bind(LDAPConnection.LDAP_V3, binddn, bindpw);
          log.debug("validateObject(): successfully bound connection [bind dn: {}]", binddn);
          conn.setBindAttempted(false);
          log.debug("validateObject(): reset connection bindAttempted flag");
        } catch (Exception e) {
          log.error("validateObject(): unable to rebind pooled connection", e);
          conn.setActive(false);
          log
              .debug("validateObject(): unset connection active flag due to bind failure, returning false");
          return false;
        }
      }

      log.debug("validateObject(): beginning connection liveness testing");

      try {
        if (!isConnectionAlive(conn)) {
          log.info("validateObject(): connection failed liveness test");
          conn.setActive(false);
          log
              .debug("validateObject(): unset connection active flag on stale connection, returning false");
          return false;
        }
      } catch (Exception e) {
        log.error("validateObject(): unable to test connection liveness", e);
        conn.setActive(false);
        log
            .debug("validateObject(): unset connection active flag due to liveness test error, returning false");
        return false;
      }

    } else {
      // we know the ref is not null
      log.debug("validateObject(): connection not of expected type [{}] nothing to do", obj
          .getClass().getName());
    }

    log.debug("validateObject(): connection appears to be valid, returning true");
    return true;
  }

  private boolean isConnectionAlive(LDAPConnection conn) {
    boolean live = false;
    if (!livenessValidators.isEmpty()) {
      for (LdapConnectionLivenessValidator validator : livenessValidators) {
        live = validator.isConnectionAlive(conn);
        if (!live) {
          break;
        }
      }
    }
    return live;
  }

  /**
   * Cleans up a PooledLDAPConnection that is about to be destroyed. The
   * finalize method in LDAPConnection handles everything, so there is nothing
   * to do here.
   */
  public void destroyObject(Object obj) throws Exception {
    log.debug("destroyObject()");
  }

  /**
   * Gives the LdapConnectionMananger that the Factory is using to configure its
   * PooledLDAPConnections.
   *
   * @return
   */
  public LdapConnectionManager getConnectionManager() {
    return connectionManager;
  }

  /**
   * Sets the LdapConnectionManager that the Factory will use to configure and
   * manage its PooledLDAPConnections. This includes gathering all the
   * connection information (host, port, user, passord), setting the
   * SocketFactory, determining if we are using TLS, and creating the default
   * constraints.
   *
   * @param connectionManager
   *          the LdapConnectionManager to use
   */
  public void setConnectionManager(LdapConnectionManager connectionManager) {

    this.connectionManager = connectionManager;

    // collect connection information
    this.host = connectionManager.getConfig().getLdapHost();
    this.port = connectionManager.getConfig().getLdapPort();
    this.autoBind = connectionManager.getConfig().isAutoBind();
    if (this.autoBind) {
      this.binddn = connectionManager.getConfig().getLdapUser();
      try {
        this.bindpw = connectionManager.getConfig().getLdapPassword().getBytes("UTF8");
      } catch (Exception e) {
        throw new RuntimeException("unable to encode bind password", e);
      }
    }

    // determine if we are using TLS
    useTLS = connectionManager.getConfig().isSecureConnection()
        && connectionManager.getConfig().isTLS();

    // set up the standard constraints
    standardConstraints = new LDAPConstraints();
    standardConstraints.setTimeLimit(connectionManager.getConfig().getOperationTimeout());
    standardConstraints.setReferralFollowing(connectionManager.getConfig().isFollowReferrals());
  }
}
