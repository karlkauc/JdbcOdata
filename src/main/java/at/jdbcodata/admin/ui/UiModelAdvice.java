package at.jdbcodata.admin.ui;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Model attributes every admin page needs. */
@ControllerAdvice(basePackages = "at.jdbcodata.admin.ui")
public class UiModelAdvice {

    @ModelAttribute("currentUser")
    public String currentUser(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    /** Request path without context path, used to highlight the active navigation entry. */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri;
    }
}
