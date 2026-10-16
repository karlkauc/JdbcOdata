package at.jdbcodata.admin.ui;

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
}
