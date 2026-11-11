package com.wannaverse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serves the UI, a static Next.js export in classpath:/static. Pages are exported as folders
 * ("/containers/" is "/containers/index.html"), so requests are resolved to those files. Hashed
 * build assets under /_next/static are cached for a year; pages are always revalidated so a new
 * version shows up right after an upgrade.
 */
@Configuration
public class StaticUiConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/_next/static/**")
                .addResourceLocations("classpath:/static/_next/static/")
                .setCacheControl(
                        CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(
                        new PathResourceResolver() {
                            @Override
                            protected Resource getResource(String path, Resource location) {
                                try {
                                    if (path.contains("..") || path.contains("\\")) return null;
                                    Resource r = resolve(path, location);
                                    // The default resolver's guard against escaping the static
                                    // folder, kept since this method replaces it.
                                    return r != null && checkResource(r, location) ? r : null;
                                } catch (IOException | RuntimeException e) {
                                    return null;
                                }
                            }

                            private Resource resolve(String path, Resource location)
                                    throws IOException {
                                if (path.startsWith("api/")) return null;
                                Resource direct = location.createRelative(path);
                                if (path.isEmpty() || path.endsWith("/")) {
                                    Resource index = location.createRelative(path + "index.html");
                                    return readable(index) ? index : null;
                                }
                                if (readable(direct) && !path.endsWith("/")) {
                                    // A folder exists as a directory resource; prefer its index.
                                    Resource index = location.createRelative(path + "/index.html");
                                    if (readable(index)) return index;
                                    return direct;
                                }
                                Resource index = location.createRelative(path + "/index.html");
                                if (readable(index)) return index;
                                Resource html = location.createRelative(path + ".html");
                                if (readable(html)) return html;
                                Resource segment = segmentFile(path, location);
                                if (segment != null) return segment;
                                // Unknown: GlobalExceptionHandler answers with the 404 page and a
                                // 404 status.
                                return null;
                            }
                        });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * Next.js prefetches route segments as "dir/__next.a.b.__PAGE__.txt"; the export stores them as
     * "dir/__next.a/b/__PAGE__.txt".
     */
    private static Resource segmentFile(String path, Resource location) throws IOException {
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash + 1) : "";
        String file = path.substring(slash + 1);
        if (!file.startsWith("__next.") || !file.endsWith(".txt")) return null;
        String inner = file.substring("__next.".length(), file.length() - ".txt".length());
        int dot = inner.indexOf('.');
        if (dot < 0 || inner.contains("..") || inner.contains("/")) return null;
        String mapped =
                dir
                        + "__next."
                        + inner.substring(0, dot)
                        + "/"
                        + inner.substring(dot + 1).replace('.', '/')
                        + ".txt";
        Resource r = location.createRelative(mapped);
        return readable(r) ? r : null;
    }

    private static boolean readable(Resource r) {
        try {
            return r.exists() && r.isReadable() && r.contentLength() >= 0 && !isDirectory(r);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isDirectory(Resource r) {
        try {
            return r.isFile() && r.getFile().isDirectory();
        } catch (IOException e) {
            return false;
        }
    }
}
