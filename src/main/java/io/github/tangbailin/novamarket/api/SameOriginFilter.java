package io.github.tangbailin.novamarket.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** Local CLI calls are allowed; cross-site browser writes to the local demonstration are rejected. */
@Component
public class SameOriginFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            boolean blocked="cross-site".equals(request.getHeader("Sec-Fetch-Site"));
            String origin=request.getHeader("Origin");
            if(origin!=null) {
                try {
                    URI u=URI.create(origin);
                    int port=u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort();
                    blocked |= !request.getScheme().equals(u.getScheme()) || !request.getServerName().equals(u.getHost()) || request.getServerPort()!=port;
                } catch(IllegalArgumentException e) { blocked=true; }
            }
            if(blocked) { response.setStatus(403); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"code\":\"ORIGIN_REJECTED\"}"); return; }
        }
        chain.doFilter(request,response);
    }
}
