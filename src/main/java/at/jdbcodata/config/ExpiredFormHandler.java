package at.jdbcodata.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * Turns a rejected CSRF token into a redirect instead of a bare 403.
 * <p>
 * The token belongs to the server session, so a form that was opened before a restart or before the session timed
 * out cannot be submitted any more. Without a valid login the user is sent to {@code /login?expired}; a signed-in
 * user goes back to the page of the form (same origin only) with a message. htmx requests get an
 * {@code HX-Redirect} header, which htmx follows. Other denials (e.g. a VIEWER posting) keep the 403.
 */
class ExpiredFormHandler implements AccessDeniedHandler {

    static final String EXPIRED_MESSAGE = "The form had expired. Please submit it again.";

    /** Same store as the DispatcherServlet's default flash map manager, so the message shows after the redirect. */
    private static final SessionFlashMapManager FLASH_MAPS = new SessionFlashMapManager();

    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException, jakarta.servlet.ServletException {
        if (!(denied instanceof CsrfException)) {
            fallback.handle(request, response, denied);
            return;
        }
        String context = request.getContextPath();
        String target;
        if (signedIn()) {
            target = sameOriginReferer(request);
            if (target == null) {
                target = context + "/admin";
            }
            // Runs in the security filter, before the DispatcherServlet set up flash attributes: store it directly.
            FlashMap flash = new FlashMap();
            flash.put("error", EXPIRED_MESSAGE);
            flash.setTargetRequestPath(target.contains("?") ? target.substring(0, target.indexOf('?')) : target);
            request.setAttribute(DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE, flash);
            FLASH_MAPS.saveOutputFlashMap(flash, request, response);
        } else {
            target = context + "/login?expired";
        }
        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Redirect", target);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        response.sendRedirect(target);
    }

    private static boolean signedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    /** Path and query of the Referer when it points to this host, otherwise null. */
    private static String sameOriginReferer(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(request.getServerName())) {
                return null;
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith(request.getContextPath() + "/admin")) {
                return null;
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
