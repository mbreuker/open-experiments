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
package org.sakaiproject.kernel.site.servlet;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.eq;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.commons.json.JSONArray;
import org.apache.sling.commons.json.JSONException;
import org.junit.Before;
import org.sakaiproject.kernel.site.AbstractSiteServiceTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;

public abstract class AbstractSiteServiceServletTest extends AbstractSiteServiceTest {

  protected SlingHttpServletRequest request;
  protected SlingHttpServletResponse response;
  protected JackrabbitSession session;

  protected abstract void makeRequest() throws ServletException, IOException;

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    request = createMock(SlingHttpServletRequest.class);
    response = createMock(SlingHttpServletResponse.class);
    session = createMock(JackrabbitSession.class);
    expect(session.getUserManager()).andReturn(userManager).anyTimes();
    expect(slingRepository.loginAdministrative((String) eq(null))).andReturn(session).anyTimes();
  }

  public byte[] makeGetRequestReturningBytes() throws IOException, ServletException
  {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintWriter writer = new PrintWriter(baos);
    expect(response.getWriter()).andReturn(writer);    
    makeRequest();
    writer.flush();
    return baos.toByteArray();
  }

  public JSONArray makeGetRequestReturningJSON() throws IOException, ServletException, JSONException
  {
    String jsonString = new String(makeGetRequestReturningBytes());
    return new JSONArray(jsonString);
  }

}
