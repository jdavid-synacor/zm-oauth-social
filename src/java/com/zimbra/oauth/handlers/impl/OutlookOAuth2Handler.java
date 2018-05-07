/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra OAuth2 Extension
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
package com.zimbra.oauth.handlers.impl;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.message.BasicNameValuePair;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.zimbra.client.ZMailbox;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.oauth.exceptions.ConfigurationException;
import com.zimbra.oauth.exceptions.GenericOAuthException;
import com.zimbra.oauth.exceptions.InvalidOperationException;
import com.zimbra.oauth.exceptions.InvalidResponseException;
import com.zimbra.oauth.exceptions.UserForbiddenException;
import com.zimbra.oauth.exceptions.UserUnauthorizedException;
import com.zimbra.oauth.handlers.IOAuth2Handler;
import com.zimbra.oauth.models.OAuthDataSource;
import com.zimbra.oauth.models.OAuthInfo;
import com.zimbra.oauth.utilities.Configuration;
import com.zimbra.oauth.utilities.OAuth2Constants;

/**
 * The OutlookOAuth2Handler class.<br>
 * Outlook OAuth operations handler.
 *
 * @author Zimbra API Team
 * @package com.zimbra.oauth.handlers.impl
 * @copyright Copyright © 2018
 */
public class OutlookOAuth2Handler extends OAuth2Handler implements IOAuth2Handler {

    /**
     * Outlook client id.
     */
    protected final String clientId;

    /**
     * Outlook client secret.
     */
    protected final String clientSecret;

    /**
     * Outlook redirect uri.
     */
    protected final String clientRedirectUri;

    /**
     * Outlook token scope.
     */
    protected final String scope;

    /**
     * DataSource handler for Outlook.
     */
    protected final OAuthDataSource dataSource;

    /**
     * Contains constants used in this implementation.
     */
    protected class OutlookConstants {

        /**
         * Invalid request error from Outlook.<br>
         * Protocol error, such as a missing required parameter.
         */
        protected static final String RESPONSE_ERROR_INVALID_REQUEST = "invalid_request";

        /**
         * Unauthorized client error from Outlook.<br>
         * The client application is not permitted to request an authorization
         * code.
         */
        protected static final String RESPONSE_ERROR_UNAUTHORIZED_CLIENT = "unauthorized_client";

        /**
         * Access denied error from Outlook.<br>
         * Resource owner denied consent.
         */
        protected static final String RESPONSE_ERROR_ACCESS_DENIED = "access_denied";

        /**
         * Server error, error from Outlook.<br>
         * The server encountered an unexpected error.
         */
        protected static final String RESPONSE_ERROR_SERVER_ERROR = "server_error";

        /**
         * Temporarily unavailable error from Outlook.<br>
         * The server is temporarily too busy to handle the request.
         */
        protected static final String RESPONSE_ERROR_TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";

        /**
         * Invalid resource error from Outlook.<br>
         * The target resource is invalid because it does not exist, Azure AD
         * cannot find it, or it is not correctly configured.
         */
        protected static final String RESPONSE_ERROR_INVALID_RESOURCE = "invalid_resource";

        /**
         * Unsupported response type error from Outlook.<br>
         * The authorization server does not support the response type in the
         * request.
         */
        protected static final String RESPONSE_ERROR_RESPONSE_TYPE = "unsupported_response_type";

        /**
         * The authorize endpoint for Outlook.
         */
        protected static final String AUTHORIZE_URI_TEMPLATE = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=%s&redirect_uri=%s&response_type=%s&scope=%s";

        /**
         * The profile endpoint for Outlook.
         */
        protected static final String PROFILE_URI = "https://www.outlookapis.com/auth/userinfo.email";

        /**
         * The authenticate endpoint for Outlook.
         */
        protected static final String AUTHENTICATE_URI = "https://login.microsoftonline.com/common/oauth2/v2.0/token";

        /**
         * The relay key for Outlook.
         */
        protected static final String RELAY_KEY = "state";

        /**
         * The scope required for Outlook.
         */
        protected static final String REQUIRED_SCOPES = "email";

        // LC Outlook
        public static final String LC_OAUTH_CLIENT_ID = "zm_oauth_outlook_client_id";
        public static final String LC_OAUTH_CLIENT_SECRET = "zm_oauth_outlook_client_secret";
        public static final String LC_OAUTH_CLIENT_REDIRECT_URI = "zm_oauth_outlook_client_redirect_uri";
        public static final String LC_OAUTH_IMPORT_CLASS = "zm_oauth_outlook_import_class";
        public static final String LC_OAUTH_SCOPE = "zm_oauth_outlook_scope";

        public static final String CLIENT_NAME = "outlook";
        public static final String HOST_OUTLOOK = "microsoftonline.com";
    }

    /**
     * Constructs an OutlookOAuth2Handler object.
     *
     * @param config For accessing configured properties
     */
    public OutlookOAuth2Handler(Configuration config) {
        super(config);
        clientId = config.getString(OutlookConstants.LC_OAUTH_CLIENT_ID);
        clientSecret = config.getString(OutlookConstants.LC_OAUTH_CLIENT_SECRET);
        clientRedirectUri = config.getString(OutlookConstants.LC_OAUTH_CLIENT_REDIRECT_URI);
        scope = StringUtils.join(new String[] { OutlookConstants.REQUIRED_SCOPES,
            config.getString(OutlookConstants.LC_OAUTH_SCOPE) }, "+");
        dataSource = OAuthDataSource.createDataSource(OutlookConstants.CLIENT_NAME,
            OutlookConstants.HOST_OUTLOOK);
    }

    @Override
    public String authorize(String relayState) throws GenericOAuthException {
        final String responseType = "code";
        String encodedRedirectUri = "";
        try {
            encodedRedirectUri = URLEncoder.encode(clientRedirectUri, OAuth2Constants.ENCODING);
        } catch (final UnsupportedEncodingException e) {
            ZimbraLog.extensions.error("Invalid redirect URI found in client config.", e);
            throw new ConfigurationException("Invalid redirect URI found in client config.");
        }

        String relayParam = "%s";
        String relayValue = "";
        String relay = StringUtils.defaultString(relayState, "");

        if (!relay.isEmpty()) {
            try {
                relay = URLDecoder.decode(relay, OAuth2Constants.ENCODING);
            } catch (final UnsupportedEncodingException e) {
                throw new InvalidOperationException("Unable to decode relay parameter.");
            }

            try {
                relayParam = "&" + OutlookConstants.RELAY_KEY + "=%s";
                relayValue = URLEncoder.encode(relay, OAuth2Constants.ENCODING);
            } catch (final UnsupportedEncodingException e) {
                throw new InvalidOperationException("Unable to encode relay parameter.");
            }
        }
        return String.format(OutlookConstants.AUTHORIZE_URI_TEMPLATE + relayParam, clientId,
            encodedRedirectUri, responseType, scope, relayValue);
    }

    @Override
    public Boolean authenticate(OAuthInfo oauthInfo) throws GenericOAuthException {
        oauthInfo.setClientId(clientId);
        oauthInfo.setClientSecret(clientSecret);
        final HttpClientContext context = HttpClientContext.create();
        final JsonNode credentials = authenticateRequest(oauthInfo, clientRedirectUri, context);

        // get zimbra mailbox
        final ZMailbox mailbox = getZimbraMailbox(oauthInfo.getZmAuthToken());

        // store username, zimbraAccountId, refreshToken
        oauthInfo.setUsername(getPrimaryEmail(credentials));
        oauthInfo.setRefreshToken(credentials.get("refresh_token").asText());
        dataSource.updateCredentials(mailbox, oauthInfo);
        return true;
    }

    @Override
    public Boolean refresh(OAuthInfo oauthInfo) throws GenericOAuthException {
        oauthInfo.setClientId(clientId);
        oauthInfo.setClientSecret(clientSecret);
        final HttpClientContext context = HttpClientContext.create();

        // get zimbra mailbox
        final ZMailbox mailbox = getZimbraMailbox(oauthInfo.getZmAuthToken());

        // get refreshToken from DataSource with end service username
        // (user@outlook.com)
        final String refreshToken = dataSource.getRefreshToken(mailbox, oauthInfo.getUsername());

        // invalid operation if no refresh token stored for the user
        if (StringUtils.isEmpty(refreshToken)) {
            throw new InvalidOperationException("The specified user has no stored refresh token.");
        }

        // add refreshToken to oauthInfo, call authenticateRequest
        oauthInfo.setRefreshToken(refreshToken);
        final JsonNode credentials = authenticateRequest(oauthInfo, clientRedirectUri, context);

        // update credentials
        oauthInfo.setRefreshToken(credentials.get("refresh_token").asText());
        dataSource.updateCredentials(mailbox, oauthInfo);
        return true;
    }

    /**
     * Builds the HTTP request for authentication.
     *
     * @param authInfo Contains the auth info to use in the request
     * @param redirectUri The user's redirect uri
     * @param context The HTTP context
     * @return Json response from the endpoint
     * @throws GenericOAuthException If there are issues performing the request
     *             or parsing for json
     */
    protected JsonNode authenticateRequest(OAuthInfo authInfo, String redirectUri,
        HttpClientContext context) throws GenericOAuthException {
        final String clientId = authInfo.getClientId();
        final String clientSecret = authInfo.getClientSecret();
        final String basicToken = Base64.getEncoder()
            .encodeToString(new String(clientId + ":" + clientSecret).getBytes());
        final String code = authInfo.getParam("code");
        final String refreshToken = authInfo.getRefreshToken();
        final HttpPost request = new HttpPost(OutlookConstants.AUTHENTICATE_URI);
        final List<NameValuePair> params = new ArrayList<NameValuePair>();
        if (!StringUtils.isEmpty(refreshToken)) {
            // set refresh token if we have one
            params.add(new BasicNameValuePair("grant_type", "refresh_token"));
            params.add(new BasicNameValuePair("refresh_token", refreshToken));
        } else {
            // otherwise use the code
            params.add(new BasicNameValuePair("grant_type", "authorization_code"));
            params.add(new BasicNameValuePair("code", code));
        }
        params.add(new BasicNameValuePair("redirect_uri", redirectUri));
        params.add(new BasicNameValuePair("client_secret", clientSecret));
        params.add(new BasicNameValuePair("client_id", clientId));
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");
        request.setHeader("Authorization", "Basic " + basicToken);
        JsonNode json = null;
        try {
            request.setEntity(new UrlEncodedFormEntity(params));
            json = executeRequestForJson(request, context);
        } catch (final IOException e) {
            ZimbraLog.extensions.error("There was an issue acquiring the authorization token.", e);
            throw new UserUnauthorizedException(
                "There was an issue acquiring an authorization token for this user.");
        }

        // ensure the response contains the necessary credentials
        validateAuthenticateResponse(json);

        return json;
    }

    /**
     * Validates that the response from authenticate has no errors, and contains
     * the requested access information.
     *
     * @param response The json response from authenticate
     * @throws InvalidOperationException If the refresh token was deemed
     *             invalid, or incorrect redirect uri
     * @throws UserUnauthorizedException If the refresh token or code is
     *             expired, or for general rejection
     * @throws UserForbiddenException If the user did not provide authorization
     *             for the same client Id used in the authenticate
     * @throws InvalidResponseException If the response from Outlook has no
     *             errors, but the access info is missing
     * @throws ConfigurationException If the client id or client secret are
     *             incorrect
     * @throws GenericOAuthException If there are issues with the response
     */
    protected void validateAuthenticateResponse(JsonNode response) throws GenericOAuthException {
        // check for errors
        if (response.has("error")) {
            final String error = response.get("error").asText();
            final JsonNode errorMsg = response.get("error_description");
            switch (error) {
            case OutlookConstants.RESPONSE_ERROR_INVALID_REQUEST:
                ZimbraLog.extensions.warn("Invalid authentication request parameters: " + errorMsg);
                throw new InvalidOperationException(
                    "The authentication request parameters are invalid.");
            case OutlookConstants.RESPONSE_ERROR_UNAUTHORIZED_CLIENT:
                ZimbraLog.extensions
                    .warn("The specified client details provided to oauth2 server are invalid: "
                        + errorMsg);
                throw new ConfigurationException(
                    "The specified client details provided to oauth2 server are invalid.");
            case OutlookConstants.RESPONSE_ERROR_ACCESS_DENIED:
                ZimbraLog.extensions
                    .info("User did not provide authorization for this service: " + errorMsg);
                throw new UserForbiddenException(
                    "User did not provide authorization for this service.");
            case OutlookConstants.RESPONSE_ERROR_SERVER_ERROR:
            case OutlookConstants.RESPONSE_ERROR_TEMPORARILY_UNAVAILABLE:
                ZimbraLog.extensions
                    .debug("There was an issue with the remote oauth2 server: " + errorMsg);
                throw new InvalidResponseException(
                    "There was an issue with the remote oauth2 server.");
            case OutlookConstants.RESPONSE_ERROR_INVALID_RESOURCE:
                ZimbraLog.extensions.debug("Invalid resource: " + errorMsg);
                throw new UserUnauthorizedException("The specified resource is invalid.");
            case OutlookConstants.RESPONSE_ERROR_RESPONSE_TYPE:
                ZimbraLog.extensions.info("Requested response type is not supported: " + errorMsg);
                throw new InvalidOperationException(
                    "Requested response type is not supported by the oauth2 server.");
            default:
                ZimbraLog.extensions
                    .warn("Unexpected error while trying to authenticate the user: " + errorMsg);
                throw new UserUnauthorizedException("Unable to authenticate the user.");
            }
        }

        // ensure the tokens we requested are present
        if (!response.has("access_token") || !response.has("refresh_token")) {
            throw new InvalidResponseException("Unexpected response from mail server.");
        }
    }

    /**
     * Retrieves the user's email address.
     *
     * @param credentials The json response from token call
     * @return The primary email address for the user
     * @throws InvalidResponseException If the email address is missing
     */
    protected String getPrimaryEmail(JsonNode credentials) throws InvalidResponseException {
        final DecodedJWT jwt = JWT.decode(credentials.get("id_token").asText());
        final Claim emailClaim = jwt.getClaim("email");
        if (emailClaim == null) {
            throw new InvalidResponseException("Authentication response is missing primary email.");
        }
        ZimbraLog.extensions.debug(jwt.getClaim("email").asString());
        return jwt.getClaim("email").asString();
    }

}
