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
package org.sakaiproject.kernel.message;

import static org.sakaiproject.kernel.api.message.MessageConstants.SAKAI_MESSAGESTORE_RT;

import org.apache.jackrabbit.util.ISO9075;
import org.apache.sling.jcr.resource.JcrResourceConstants;
import org.sakaiproject.kernel.api.locking.LockManager;
import org.sakaiproject.kernel.api.locking.LockTimeoutException;
import org.sakaiproject.kernel.api.message.MessageConstants;
import org.sakaiproject.kernel.api.message.MessagingException;
import org.sakaiproject.kernel.api.message.MessagingService;
import org.sakaiproject.kernel.api.site.SiteException;
import org.sakaiproject.kernel.api.site.SiteService;
import org.sakaiproject.kernel.util.DateUtils;
import org.sakaiproject.kernel.util.JcrUtils;
import org.sakaiproject.kernel.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFormatException;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;

/**
 * Service for doing operations with messages.
 * 
 * @scr.component immediate="true" label="Sakai Messaging Service"
 *                description="Service for doing operations with messages."
 *                name="org.sakaiproject.kernel.api.message.MessagingService"
 * @scr.property name="service.vendor" value="The Sakai Foundation"
 * @scr.service interface="org.sakaiproject.kernel.api.message.MessagingService"
 * @scr.reference interface="org.sakaiproject.kernel.api.site.SiteService" name="SiteService"
 */
public class MessagingServiceImpl implements MessagingService {

  /** @scr.reference */
  private LockManager lockManager;
  private SiteService siteService;
  protected void bindSiteService(SiteService siteService) {
    this.siteService = siteService;
  }
  protected void unbindSiteService(SiteService siteService) {
    this.siteService = null;
  }
  
  private static final Logger LOGGER = LoggerFactory
      .getLogger(MessagingServiceImpl.class);

  public List<String> getMailboxesForEmailAddress(Session session, String emailAddress) throws InvalidQueryException, RepositoryException {
    String queryString = "/" + MessageConstants._USER_MESSAGE + "//element(*)MetaData[@sling:resourceType='" 
                             + MessageConstants.SAKAI_MESSAGESTORE_RT + "' and @" 
                             + MessageConstants.SAKAI_EMAIL_ADDRESS + "='" + emailAddress + "']";
    Query query = session.getWorkspace().getQueryManager().createQuery(queryString, "xpath");
    QueryResult result = query.execute();
    NodeIterator iter = result.getNodes();
    List<String> mailboxes = new ArrayList<String>();
    while (iter.hasNext()) {
      mailboxes.add(iter.nextNode().getName());
    }
    return mailboxes;
  }
  /**
   * 
   * {@inheritDoc}
   * 
   * @throws MessagingException
   * 
   * @see org.sakaiproject.kernel.api.message.MessagingService#create(org.apache.sling.api.resource.Resource)
   */
  public Node create(Session session, Map<String, Object> mapProperties)
      throws MessagingException {
    return create(session, mapProperties, null);
  }
  
  private String generateMessageId() {
    String messageId = String.valueOf(Thread.currentThread().getId())
        + String.valueOf(System.currentTimeMillis());
    try {
      return messageId = org.sakaiproject.kernel.util.StringUtils.sha1Hash(messageId);
    } catch (Exception ex) {
      throw new MessagingException("Unable to create hash.");
    }
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @throws MessagingException
   * 
   * @see org.sakaiproject.kernel.api.message.MessagingService#create(org.apache.sling.api.resource.Resource)
   */
  public Node create(Session session, Map<String, Object> mapProperties, String messageId)
      throws MessagingException {

    Node msg = null;
    if (messageId == null) {
      messageId = generateMessageId();
    }

    String user = session.getUserID();
    String messagePathBase = getFullPathToStore(user, session);
    try {
      lockManager.waitForLock(messagePathBase);
    } catch (LockTimeoutException e1) {
      throw new MessagingException("Unable to lock user mailbox");
    }
    try {
      //String messagePath = MessageUtils.getMessagePath(user, ISO9075.encodePath(messageId));
      String messagePath = getFullPathToMessage(user, messageId, session);
      try {
        msg = JcrUtils.deepGetOrCreateNode(session, messagePath);
        
        for (Entry<String, Object> e : mapProperties.entrySet()) {
          msg.setProperty(e.getKey(), e.getValue().toString());
        }
        // Add the id for this message.
        msg.setProperty(MessageConstants.PROP_SAKAI_ID, messageId);
        msg.setProperty(MessageConstants.PROP_SAKAI_CREATED, DateUtils.rfc3339());
        
        if (session.hasPendingChanges()) {
          session.save();
        }
        
      } catch (RepositoryException e) {
        LOGGER.warn("RepositoryException on trying to save message."
            + e.getMessage());
        e.printStackTrace();
        throw new MessagingException("Unable to save message.");
      }
      return msg;
    } finally {
      lockManager.clearLocks();
    }
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.kernel.api.message.MessagingService#getMessageStorePathFromMessageNode(javax.jcr.Node)
   */
  public String getMessageStorePathFromMessageNode(Node msg)
      throws ValueFormatException, PathNotFoundException,
      ItemNotFoundException, AccessDeniedException, RepositoryException {
    Node n = msg;
    while (!"/".equals(n.getPath())) {
      if (n.hasProperty(JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY)
          && SAKAI_MESSAGESTORE_RT.equals(n.getProperty(
              JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY).getString())) {
        return n.getPath();
      }
      n = n.getParent();
    }
    return null;
  }

  /**
   * 
   * {@inheritDoc}
   * @throws RepositoryException 
   * @throws PathNotFoundException 
   * @see org.sakaiproject.kernel.api.message.MessagingService#copyMessage(java.lang.String, java.lang.String, java.lang.String)
   */
  public void copyMessage(Session adminSession, String target, String source, String messageId) throws PathNotFoundException, RepositoryException {
    String encodedMessageId = ISO9075.encodePath(messageId);
    String sourceNodePath = getFullPathToMessage(source, encodedMessageId, adminSession);
    String targetNodePath = getFullPathToMessage(target, encodedMessageId, adminSession);
    String parent = targetNodePath.substring(0, targetNodePath.lastIndexOf('/'));
    Node parentNode = JcrUtils.deepGetOrCreateNode(adminSession, parent);
    LOGGER.info("Created parent node at: " + parentNode.getPath());
    adminSession.save();
    adminSession.getWorkspace().copy(sourceNodePath, targetNodePath);
  }


  /**
   * 
   * {@inheritDoc}
   * @see org.sakaiproject.kernel.api.message.MessagingService#isMessageStore(javax.jcr.Node)
   */
  public boolean isMessageStore(Node n) {
    try {
      if (n.hasProperty(JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY)
          && MessageConstants.SAKAI_MESSAGESTORE_RT.equals(n.getProperty(
              JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY).getString())) {
        return true;
      }
    } catch (RepositoryException e) {
      return false;
    }

    return false;
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.kernel.api.message.MessagingService#getFullPathToMessage(java.lang.String,
   *      java.lang.String)
   */
  public String getFullPathToMessage(String rcpt, String messageId, Session session) throws MessagingException {
    String storePath = getFullPathToStore(rcpt, session);
    return PathUtils.toInternalHashedPath(storePath, messageId, "");
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.kernel.api.message.MessagingService#getFullPathToStore(java.lang.String)
   */
  public String getFullPathToStore(String rcpt, Session session) throws MessagingException {
    String path = "";
    try {
      if (rcpt.startsWith("s-")) {
        // This is a site.
        Node n = siteService.findSiteByName(session, rcpt.substring(2));
        path = n.getPath() + "/store";
      } else if (rcpt.startsWith("g-")) {
        // This is a group.
        path = PathUtils.toInternalHashedPath(MessageConstants._GROUP_MESSAGE, rcpt, "");
      } else {
        // Assume that it is a user.
        path = PathUtils.toInternalHashedPath(MessageConstants._USER_MESSAGE, rcpt, "");
      }
    } catch (SiteException e) {
      LOGGER.warn("Caught SiteException when trying to get the full path to {} store.", rcpt);
      e.printStackTrace();
      throw new MessagingException(e);
    } catch (RepositoryException e) {
      LOGGER.warn("Caught RepositoryException when trying to get the full path to {} store.", rcpt);
      e.printStackTrace();
      throw new MessagingException(e);
    }

    return path;
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.kernel.api.message.MessagingService#getUriToMessage(java.lang.String,
   *      java.lang.String)
   */
  public String getUriToMessage(String rcpt, String messageId, Session session) throws MessagingException {
    String storePath = getUriToStore(rcpt, session);
    return storePath + "/" + messageId;
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.kernel.api.message.MessagingService#getUriToStore(java.lang.String)
   */
  public String getUriToStore(String rcpt, Session session) throws MessagingException {
    String path = "";
    try {
      if (rcpt.startsWith("s-")) {
        // This is a site.
        Node n = siteService.findSiteByName(session, rcpt);
        path = n.getPath() + "/store";
      } else if (rcpt.startsWith("g-")) {
        // This is a group.
        path = MessageConstants._GROUP_MESSAGE + "/" + rcpt;
      } else {
        // Assume that it is a user.
        path = MessageConstants._USER_MESSAGE + "/" + rcpt;
      }
    } catch (SiteException e) {
      e.printStackTrace();
      throw new MessagingException(e);
    } catch (RepositoryException e) {
      e.printStackTrace();
      throw new MessagingException(e);
    }

    return path;
  }

}
