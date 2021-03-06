/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra OAuth Social Extension
 * Copyright (C) 2018 Synacor, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.oauth.resources;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang.StringUtils;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.extension.ExtensionHttpHandler;
import com.zimbra.oauth.utilities.OAuth2Constants;
import com.zimbra.oauth.utilities.OAuth2ErrorConstants;
import com.zimbra.oauth.utilities.OAuth2HttpConstants;
import com.zimbra.oauth.utilities.OAuth2ResourceUtilities;

/**
 * The ZOAuth2Servlet class.<br>
 * Request entry point for the project.
 *
 * @author Zimbra API Team
 * @package com.zimbra.oauth.resources
 * @copyright Copyright © 2018
 */
public class ZOAuth2Servlet extends ExtensionHttpHandler {

    @Override
    public String getPath() {
        return OAuth2Constants.DEFAULT_SERVER_PATH.getValue();
    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws IOException, ServletException {
        final String path = StringUtils.removeEndIgnoreCase(req.getPathInfo(), "/");
        if (!isValidPath(path)) {
            // invalid location - not part of this service
            resp.sendError(Status.BAD_REQUEST.getStatusCode());
            return;
        }
        final Map<String, String> pathParams = parseRequestPath(path);
        final String client = pathParams.get("client");
        String location = OAuth2Constants.DEFAULT_SUCCESS_REDIRECT.getValue();

        try {
            // handle request action
            switch (pathParams.get("action")) {
            case "authorize":
                location = OAuth2ResourceUtilities.authorize(client, req.getCookies(),
                    getHeaders(req), req.getParameterMap());
                break;
            case "authenticate":
                location = OAuth2ResourceUtilities.authenticate(client, req.getCookies(),
                    getHeaders(req), req.getParameterMap());
                break;
            default:
                // missing valid action - bad request
                resp.sendError(Status.BAD_REQUEST.getStatusCode());
                return;
            }
        } catch (final ServiceException e) {
            ZimbraLog.extensions.errorQuietly("An oauth application error occurred.", e);
            // default to unhandled
            OAuth2ErrorConstants error = OAuth2ErrorConstants.ERROR_UNHANDLED_ERROR;
            if (StringUtils.equals(ServiceException.PERM_DENIED, e.getCode())) {
                error = OAuth2ErrorConstants.ERROR_ACCESS_DENIED;
            } else if (StringUtils.equals(ServiceException.UNSUPPORTED, e.getCode())) {
                error = OAuth2ErrorConstants.ERROR_INVALID_CLIENT;
            }
            resp.sendRedirect(OAuth2ResourceUtilities.addQueryParams(
                OAuth2Constants.DEFAULT_SUCCESS_REDIRECT.getValue(),
                OAuth2ResourceUtilities.mapError(error.getValue(), null)));
            return;
        }
        ZimbraLog.extensions.debug("Authorization URI:%s", location);
        // set response redirect location
        resp.sendRedirect(location);
    }

    /**
     * @param req The current request
     * @return A map of headers we need for authorize and authenticate
     */
    private Map<String, String> getHeaders(HttpServletRequest req) {
        final Map<String, String> headers = new HashMap<String, String>();
        headers.put(OAuth2HttpConstants.HEADER_AUTHORIZATION.getValue(),
            req.getHeader(OAuth2HttpConstants.HEADER_AUTHORIZATION.getValue()));
        return headers;
    }

    /**
     * Determines if the path is one serviced by this extension.
     *
     * @param path The path to check
     * @return True if the op is serviceable
     */
    protected boolean isValidPath(String path) {
        return StringUtils.containsIgnoreCase(path, "authenticate/")
            || StringUtils.containsIgnoreCase(path, "authorize/");
    }

    /**
     * Parses the path for the request path parameters.
     *
     * @param path The path to parse
     * @return Path parameters
     */
    protected Map<String, String> parseRequestPath(String path) {
        final Map<String, String> pathParams = new HashMap<String, String>();
        final String[] parts = path.split("/");
        // action
        if (StringUtils.equalsIgnoreCase(parts[2], "authorize")
            || StringUtils.equalsIgnoreCase(parts[2], "authenticate")) {
            pathParams.put("action", parts[2]);
        }
        // client
        if (StringUtils.isNotEmpty(parts[3])) {
            pathParams.put("client", parts[3]);
        }
        return pathParams;
    }

}
