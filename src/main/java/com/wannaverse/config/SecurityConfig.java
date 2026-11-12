package com.wannaverse.config;

import com.wannaverse.iac.ConfigLockInterceptor;
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
    private final ConfigLockInterceptor configLockInterceptor;

    public SecurityConfig(
            PermissionInterceptor permissionInterceptor,
            CurrentUserArgumentResolver currentUserArgumentResolver,
            ConfigLockInterceptor configLockInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.configLockInterceptor = configLockInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/api/auth/logout",
                        "/api/system/status",
                        "/api/ingress/acme/**",
                        "/api/git/webhook/**",
                        // Authenticated by the secret in the URL, like git webhooks.
                        "/api/pipelines/webhook/**",
                        // The configuration file's JSON schema, for editors. Holds no data.
                        "/api/config/schema");
        // After the permission check: say "managed by the file" only to people allowed to act.
        registry.addInterceptor(configLockInterceptor).addPathPatterns("/api/**");
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
