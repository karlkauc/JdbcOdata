package at.jdbcodata.odata;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ODataConfig {

    public static final String BASE_PATH = "/odata";

    @Bean
    ServletRegistrationBean<ODataServlet> odataServlet(ODataServlet servlet) {
        ServletRegistrationBean<ODataServlet> bean = new ServletRegistrationBean<>(servlet, BASE_PATH + "/*");
        bean.setName("odata");
        bean.setLoadOnStartup(1);
        return bean;
    }
}
