package at.jdbcodata.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * How session attributes are stored. An update of Spring Security can leave stored sessions unreadable; without this,
 * their users would get an error on every request until the session times out. Now they just sign in again.
 */
@Configuration
class SessionStorage {

    private static final Logger log = LoggerFactory.getLogger(SessionStorage.class);

    @Bean
    ConversionService springSessionConversionService() {
        DeserializingConverter deserializer = new DeserializingConverter(getClass().getClassLoader());
        GenericConversionService service = new GenericConversionService();
        service.addConverter(Object.class, byte[].class, new SerializingConverter());
        service.addConverter(byte[].class, Object.class, bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (SerializationFailedException e) {
                log.info("Ignoring a stored session attribute that cannot be read: {}", e.getMessage());
                return null;
            }
        });
        return service;
    }
}
