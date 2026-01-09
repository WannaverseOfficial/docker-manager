package com.wannaverse.config;

import com.wannaverse.security.CurrentUserArgumentResolver;
import com.wannaverse.security.PermissionInterceptor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public SecurityConfig(
            PermissionInterceptor permissionInterceptor,
            CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.permissionInterceptor = permissionInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login", // Login must be public
                        "/api/auth/refresh", // Token refresh must be public
                        "/api/ingress/acme/**", // ACME challenge must be public
                        "/api/git/webhook/**"); // Webhooks are authenticated via secret
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
