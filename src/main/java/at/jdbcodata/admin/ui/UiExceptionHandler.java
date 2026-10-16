package at.jdbcodata.admin.ui;

import at.jdbcodata.common.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(basePackages = "at.jdbcodata.admin.ui")
public class UiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NotFoundException e, org.springframework.ui.Model model) {
        model.addAttribute("error", e.getMessage());
        return "error/404";
    }
}
