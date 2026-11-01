package at.jdbcodata.admin.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Keeps accounts with a temporary password away from everything but the password page. Part of the security chains,
 * not a servlet filter. The table is asked on every request, so a reset also reaches a running session.
 */
public class PasswordChangeFilter extends OncePerRequestFilter {

    public static final String PAGE = "/admin/password";

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final AdminUserService admins;

    public PasswordChangeFilter(AdminUserService admins) {
        this.admins = admins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PATHS.getPathWithinApplication(request);
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        if (user == null || user instanceof AnonymousAuthenticationToken || !user.isAuthenticated() || allowed(path)
                || !admins.mustChangePassword(user.getName())) {
            chain.doFilter(request, response);
        } else if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"detail\":\"Change the temporary password in the admin UI first\"}");
        } else {
            response.sendRedirect(request.getContextPath() + PAGE);
        }
    }

    private static boolean allowed(String path) {
        return path.equals(PAGE) || path.equals("/login") || path.equals("/logout") || path.equals("/error")
                || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/webjars/");
    }
}
