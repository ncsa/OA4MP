package edu.uiuc.ncsa.myproxy.oa4mp.oauth2.servlet;

import edu.uiuc.ncsa.myproxy.oa4mp.oauth2.OA2SE;
import edu.uiuc.ncsa.myproxy.oa4mp.oauth2.OA2ServiceTransaction;
import edu.uiuc.ncsa.myproxy.oa4mp.server.servlet.EnvServlet;
import edu.uiuc.ncsa.myproxy.oa4mp.server.servlet.MyProxyDelegationServlet;
import edu.uiuc.ncsa.myproxy.oa4mp.server.servlet.PresentationState;
import edu.uiuc.ncsa.security.core.exceptions.GeneralException;
import edu.uiuc.ncsa.security.core.util.BasicIdentifier;
import edu.uiuc.ncsa.security.delegation.token.impl.AuthorizationGrantImpl;
import edu.uiuc.ncsa.security.oauth_2_0.OA2Errors;
import edu.uiuc.ncsa.security.oauth_2_0.OA2GeneralError;
import edu.uiuc.ncsa.security.servlet.JSPUtil;
import edu.uiuc.ncsa.security.servlet.PresentableState;
import edu.uiuc.ncsa.security.servlet.ServletDebugUtil;
import org.apache.http.HttpStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static edu.uiuc.ncsa.myproxy.oa4mp.oauth2.servlet.OA2AuthorizationServer.AUTHORIZATION_PASSWORD_KEY;
import static edu.uiuc.ncsa.myproxy.oa4mp.oauth2.servlet.OA2AuthorizationServer.AUTHORIZATION_USER_NAME_KEY;
import static edu.uiuc.ncsa.myproxy.oa4mp.oauth2.servlet.RFC8628Servlet.cache;
import static edu.uiuc.ncsa.myproxy.oa4mp.server.servlet.AbstractAuthorizationServlet.*;
import static edu.uiuc.ncsa.security.core.util.StringUtils.isTrivial;
import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

/**
 * <p>Created by Jeff Gaynor<br>
 * on 2/12/21 at  6:19 AM
 */
public class RFC8628AuthorizationServer extends EnvServlet {
    int DEFAULT_RETRY_COUNT = 3;

    public static final String USER_CODE_KEY = "AuthUserCode";

    protected String getInitialPage() {
        return "/device-init.jsp";
    }

    protected String getRemoteUserInitialPage() {
        return "/device-remote-user.jsp";
    }

    protected String getOkPage() {
        return "/device-ok.jsp";
    }

    protected String getFailPage() {
        return "/device-fail.jsp";
    }

    @Override
    public void storeUpdates() throws IOException, SQLException {
        // no op.
    }

    protected OA2SE getServiceEnvironment() {
        return (OA2SE) MyProxyDelegationServlet.getServiceEnvironment();
    }

    public void prepare(PresentableState state) throws Throwable {
        PendingState pendingState = (PendingState) state;
        switch (pendingState.getState()) {
            case AUTHORIZATION_ACTION_OK:
                // nothing to do, really
                return;
            case AUTHORIZATION_ACTION_START:
                info("3.a. Starting authorization device");
                //Mess of information for the form
                setClientRequestAttributes(pendingState);
                return;
        }
    }

    /**
     * A class that is used by the authorization server to track user retries.
     * These only exist here and are only managed here.
     *
     */
    public static class PendingState extends PresentationState {
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        String username;
        String id;
        int count = 0;
        long expiresAt = 0L;

        public boolean isExpired() {
            return expiresAt < System.currentTimeMillis();
        }

        public PendingState(int state,
                            HttpServletRequest httpServletRequest,
                            HttpServletResponse httpServletResponse,
                            String id) {
            super(state, httpServletRequest, httpServletResponse);
            this.id = id;
        }
    }

    protected void setClientRequestAttributes(PendingState pendingState) {
        HttpServletRequest request = pendingState.getRequest();
        request.setAttribute(AUTHORIZATION_USER_NAME_KEY, AUTHORIZATION_USER_NAME_KEY);
        request.setAttribute(AUTHORIZATION_PASSWORD_KEY, AUTHORIZATION_PASSWORD_KEY);
        request.setAttribute(AUTHORIZATION_ACTION_KEY, AUTHORIZATION_ACTION_KEY);
        request.setAttribute(USER_CODE_KEY, USER_CODE_KEY);
        request.setAttribute("actionOk", AUTHORIZATION_ACTION_OK_VALUE);
        request.setAttribute("identifier", pendingState.id);
        request.setAttribute("count", Integer.toString(pendingState.count));
    }

    public void postprocess(PendingState pendingState) throws Throwable {
        pendingState.getResponse().setHeader("X-Frame-Options", "DENY");
    }

    @Override
    protected void doIt(HttpServletRequest request, HttpServletResponse response) throws Throwable {
        PendingState pendingState = null;

        switch (getState(request)) {
            case AUTHORIZATION_ACTION_OK:
                cleanupPending(); // get rid of any old ones before looking.
                try {
                    String id = request.getParameter("identifier");
                    if (isTrivial(id)) {
                        throw new OA2GeneralError(OA2Errors.INVALID_REQUEST, "no pending flow found", HttpStatus.SC_BAD_REQUEST, null);
                    }
                    pendingState = pending.get(id);
                    if (pendingState == null) {
                        throw new OA2GeneralError(OA2Errors.INVALID_REQUEST, "no pending flow found", HttpStatus.SC_BAD_REQUEST, null);
                    }
                    prepare(pendingState);
                    processRequest(request, response, pendingState);
                    JSPUtil.fwd(request, response, getOkPage());
                    return;

                } catch (GeneralSecurityException t) {
                    // Generic failure
                    info("Prompting user to retry login");
                    request.setAttribute(RETRY_MESSAGE, getServiceEnvironment().getMessages().get(RETRY_MESSAGE));
                    pendingState.setState(AUTHORIZATION_ACTION_START);
                    prepare(pendingState);
                } catch (TooManyRetriesException userErrorCodeException) {
                    info("Too many retries for user code, aborting.");
                    JSPUtil.fwd(request, response, getFailPage());
                    return;
                } catch (UserLoginException  | UnknownUserCodeException userLoginException) {
                    info("Prompting user to retry login");
                    userLoginException.printStackTrace();
                    request.setAttribute(RETRY_MESSAGE, userLoginException.getMessage());
                    pendingState.setState(AUTHORIZATION_ACTION_START);
                    prepare(pendingState);
                }
                break;
            case AUTHORIZATION_ACTION_START:
                String id = BasicIdentifier.randomID().toString();
                pendingState = new PendingState(getState(request),
                        request,
                        response,
                        id);
                pendingState.count = DEFAULT_RETRY_COUNT;
                pendingState.expiresAt = System.currentTimeMillis() + getServiceEnvironment().getAuthorizationGrantLifetime();
                pending.put(id, pendingState);
                prepare(pendingState);
                break;
            default:
                // nothing to do here either.
        }
        present(pendingState);
    }

    /**
     * This is where the user's log in is actually processed and the values they sent are checked.
     *
     * @param request
     * @param httpServletResponse
     * @throws Throwable
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse httpServletResponse, PendingState pendingState) throws Throwable {
        ServletDebugUtil.trace(this, "starting servlet");
        String userName = null;
        String password = null;
        String userCode = null;
        // Check that they have not exceeded their retry count:
        String counter = request.getParameter("counter");

        if (isTrivial(counter)) {
            throw new TooManyRetriesException("Retry attempts exceeded", "");
        }
        int count = 0;
        try {
            count = Integer.parseInt(counter);

        } catch (Throwable t) {
            throw new OA2GeneralError(OA2Errors.SERVER_ERROR, "counter not a number", HttpStatus.SC_INTERNAL_SERVER_ERROR, null);
        }
        if (count < 1) {
                      pending.remove(pendingState.id); // remove state, so they can't retry this somehow
                      ServletDebugUtil.trace(this, "user \"" + pendingState.getUsername() + "\" exceeded retry count.");
                      throw new TooManyRetriesException("retry attempts exceeded", "");
                  }
        pendingState.count--;


        // Fixes OAUTH-192.
        if (getServiceEnvironment().getAuthorizationServletConfig().isUseHeader()) {
            String headerName = getServiceEnvironment().getAuthorizationServletConfig().getHeaderFieldName();
            if (isTrivial(headerName) || headerName.toLowerCase().equals("remote_user")) {
                userName = request.getRemoteUser();
            } else {
                Enumeration enumeration = request.getHeaders(headerName);
                if (!enumeration.hasMoreElements()) {
                    throw new OA2GeneralError(OA2Errors.ACCESS_DENIED,
                            "Error: A custom header of \"" + headerName + "\" was specified for authorization, but no value was found.",
                            HttpStatus.SC_UNAUTHORIZED, null);
                }
                userName = enumeration.nextElement().toString();
                if (enumeration.hasMoreElements()) {
                    throw new OA2GeneralError(OA2Errors.ACCESS_DENIED,
                            "Error: A custom header of \"" + headerName + "\" was specified for authorization, but multiple values were found.",
                            HttpStatus.SC_UNAUTHORIZED, null);
                }
            }
            if (getServiceEnvironment().getAuthorizationServletConfig().isRequireHeader()) {
                if (isTrivial(userName)) {
                    warn("Headers required, but none found.");
                    throw new OA2GeneralError(OA2Errors.ACCESS_DENIED,
                            "Headers required, but none found.",
                            HttpStatus.SC_UNAUTHORIZED, null);
                }
            } else {
                // So the score card is that the header is not required though use it if there for the username
                if (isTrivial(userName)) {
                    userName = request.getParameter(AUTHORIZATION_USER_NAME_KEY);
                }

            }
        } else {
            // Headers not used, just pull it off the form the user POSTs.
            userName = request.getParameter(AUTHORIZATION_USER_NAME_KEY);
            password = request.getParameter(AUTHORIZATION_PASSWORD_KEY);
            if (DEBUG_LOGIN) {
                debugCheckUser(userName, password);
            } else {
                checkUser(userName, password);
            }
            pendingState.setUsername(userName);
        }
        userCode = request.getParameter(USER_CODE_KEY);

        if (!isTrivial(userCode)) {
            userCode = userCode.toUpperCase();
        }
        if (!cache.containsKey(userCode)) {
            if(pendingState.count == 0){
                throw new TooManyRetriesException("number of retries has been been reached,", userCode);
            }
            throw new UnknownUserCodeException("unknown user code", userCode);
        }
        AuthorizationGrantImpl ag = new AuthorizationGrantImpl(URI.create(cache.get(userCode)));
        if (ag.isExpired()) {
            throw new OA2GeneralError(OA2Errors.INVALID_GRANT, "expired grant", HttpStatus.SC_BAD_REQUEST, null);
        }
        OA2ServiceTransaction trans = (OA2ServiceTransaction) getServiceEnvironment().getTransactionStore().get(ag);
        if (trans == null) {
            throw new OA2GeneralError(OA2Errors.INVALID_GRANT, "grant is invalid", HttpStatus.SC_BAD_REQUEST, null);
        }
        if (!trans.isAuthGrantValid()) {
            throw new OA2GeneralError(OA2Errors.INVALID_GRANT, "grant is invalid", HttpStatus.SC_BAD_REQUEST, null);
        }

        if (!trans.isRFC8628Request()) {
            //So there is such a grant but somehow this is not a valid rfc 8628 request. Should not happen, but if someone edited
            // the transaction itself and made a mistake, it could, in which case the state of the request itself is questionable.
            throw new OA2GeneralError(OA2Errors.INVALID_REQUEST, "invalid request", HttpStatus.SC_BAD_REQUEST, null);
        }
        trans.setUsername(userName);
        RFC8628State rfc8628State = trans.getRFC8628State();
        rfc8628State.valid = true; // means they actually logged in
        // The JSON library copies everything no matter what, so no guarantee what's in the transaction is the same object.
        // Just replace it with the good copy.
        trans.setRFC8628State(rfc8628State);
        pending.remove(pendingState.id); // clean that out
        getServiceEnvironment().getTransactionStore().save(trans);
    }

    public static class TooManyRetriesException extends GeneralException {
        String userCode;

        public TooManyRetriesException(String message, String userCode) {
            super(message);
            this.userCode = userCode;
        }
    }

    public static class UnknownUserCodeException extends GeneralException {
        String userCode;

        public UnknownUserCodeException(String message, String userCode) {
            super(message);
            this.userCode = userCode;
        }
    }

    // Only set to true if you are debugging the login machinery.
    // It then allows exactly one user to authenticate.
    boolean DEBUG_LOGIN = false;

    public void debugCheckUser(String username, String password) throws GeneralSecurityException {
        if (username.equals("jeff") && password.equals("changeme")) {
            return;
        }
    }

    public void checkUser(String username, String password) throws GeneralSecurityException {
        // At this point in the basic servlet, there is no system for passwords.
        // This is because OA4MP has no native concept of managing users, it being
        // far outside of the OAuth spec.
        // If you were checking users and there  were a problem, you would do this:
        String message = "invalid login";
        throw new OA2GeneralError(OA2Errors.ACCESS_DENIED, message, HttpStatus.SC_UNAUTHORIZED, null);

        // which would display the message as the retry message.

    }



    Map<String, PendingState> pending = new HashMap<>();

    public void present(PresentableState state) throws Throwable {
        PendingState pendingState = (PendingState) state;
        postprocess(pendingState);

        switch (pendingState.getState()) {
            case AUTHORIZATION_ACTION_START:
                String initPage = getInitialPage();
                info("*** STARTING present");
                if (getServiceEnvironment().getAuthorizationServletConfig().isUseHeader()) {
                    initPage = getRemoteUserInitialPage();

                    info("*** PRESENT: Use headers enabled.");
                    String x = null;
                    if (getServiceEnvironment().getAuthorizationServletConfig().getHeaderFieldName().equals("REMOTE_USER")) {
                        // slightly more surefire way to get this.
                        x = pendingState.getRequest().getRemoteUser();
                        info("*** got user name from request = " + x);
                    } else {
                        x = pendingState.getRequest().getHeader(getServiceEnvironment().getAuthorizationServletConfig().getHeaderFieldName());
                        info("Got username from header \"" + getServiceEnvironment().getAuthorizationServletConfig().getHeaderFieldName() + "\" + directly: " + x);
                    }

                    if (isTrivial(x)) {
                        if (getServiceEnvironment().getAuthorizationServletConfig().isRequireHeader()) {
                            throw new GeneralException("Error: configuration required using the header \"" +
                                    getServiceEnvironment().getAuthorizationServletConfig().getHeaderFieldName() + "\" " +
                                    "but this was not set. Cannot continue."
                            );
                        }
                        // not required, it is null

                    } else {
                        // name is set. optional or required
                        pendingState.setUsername(x);
                        info("*** storing user name = " + x);
                        //getTransactionStore().save(aState.getTransaction());

                        // make it display pretty as per usual conventions. This is never reused, however.
                        pendingState.getRequest().setAttribute(AUTHORIZATION_USER_NAME_VALUE, escapeHtml(x));
                    }
                } else {
                    info("*** PRESENT: Use headers DISABLED.");

                }
                JSPUtil.fwd(state.getRequest(), state.getResponse(), initPage);
                info("3.a. User information obtained for grant = " + pendingState.id);
                break;
            case AUTHORIZATION_ACTION_OK:
                JSPUtil.fwd(state.getRequest(), state.getResponse(), getOkPage());
                break;
            default:
                // fall through and do nothing
                debug("Hit default case in " + this.getClass().getSimpleName() + " servlet");
        }
    }

    protected void cleanupPending() {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Set<String> keys = pending.keySet();
        for (String key : keys) {
            if (pending.get(key).isExpired()) {
                pending.remove(key);
            }
        }
    }
}
