package com.freecontent.community.auth.config;

import com.freecontent.community.auth.security.AdminAuthorizationManager;
import com.freecontent.community.auth.security.BearerSessionFilter;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    public AdminAuthorizationManager adminAuthorizationManager() {
        return new AdminAuthorizationManager();
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, SessionAuthenticationService authentication,
            AdminAuthorizationManager adminAuthorization,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        http.cors(Customizer.withDefaults())
                // 仅接受显式Authorization Bearer，不接受Cookie或HttpSession身份。
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, cause) -> errors.resolveException(
                                request, response, null, new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)))
                        .accessDeniedHandler((request, response, cause) -> errors.resolveException(
                                request, response, null, new BusinessException(ErrorCode.PERMISSION_DENIED))))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, BearerSessionFilter.PUBLIC_AUTH_PATHS.toArray(String[]::new)).permitAll()
                        .requestMatchers(HttpMethod.GET, BearerSessionFilter.PUBLIC_READ_PATHS.toArray(String[]::new)).permitAll()
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/**").access(adminAuthorization)
                        .anyRequest().authenticated())
                .addFilterBefore(new BearerSessionFilter(authentication, errors), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
