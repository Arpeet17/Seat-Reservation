package dev.seatres.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.seatres.error.ApiException;
import dev.seatres.error.ErrorCode;

/** Injects the verified Principal into controller methods, or fails the request with 401. */
@Configuration
public class PrincipalArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Principal.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        Object p = request.getNativeRequest(HttpServletRequest.class).getAttribute(AuthFilter.ATTR_PRINCIPAL);
        if (p == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "missing or invalid bearer token");
        }
        return p;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
