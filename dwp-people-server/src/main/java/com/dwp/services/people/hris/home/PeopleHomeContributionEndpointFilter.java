package com.dwp.services.people.hris.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exact workload-only GET endpoint. It precedes the browser-oriented People
 * security filter so caller tenant/user headers are neither needed nor accepted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = PeopleHomeAuthorityPort.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class PeopleHomeContributionEndpointFilter extends OncePerRequestFilter {

    static final String PREFIX = "/internal/hris-people/v1/home-contributions/";
    private static final String FAMILY = "/internal/hris-people/v1/home-contributions";
    private final PeopleHomeAdmissionService admission;
    private final PeopleHomeContributionService contributions;
    private final ObjectMapper mapper;

    PeopleHomeContributionEndpointFilter(
            PeopleHomeAdmissionService admission,
            PeopleHomeContributionService contributions,
            ObjectMapper mapper) {
        this.admission = admission;
        this.contributions = contributions;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.equals(FAMILY)
                || path.startsWith(FAMILY + "/") || path.startsWith(FAMILY + ";"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            String path = request.getRequestURI();
            if (!"GET".equals(request.getMethod()) || request.getQueryString() != null
                    || request.getContentLengthLong() > 0
                    || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null
                    || !path.startsWith(PREFIX)) {
                write(response, HttpServletResponse.SC_BAD_REQUEST,
                        "EXACT_BODYLESS_HOME_MATERIALIZATION_GET_REQUIRED");
                return;
            }
            String payloadRef = path.substring(PREFIX.length());
            PeopleHomeAdmissionService.Admission admitted =
                    admission.admit(path, headers(request));
            var materialization = contributions.materialize(
                    payloadRef, admitted.authority(),
                    admitted.verified().claims().correlationId());
            response.setStatus(HttpServletResponse.SC_OK);
            mapper.writeValue(response.getOutputStream(), materialization);
        } catch (HomeMaterializationWorkloadProofV1.AdmissionDenied denied) {
            write(response, HttpServletResponse.SC_FORBIDDEN, denied.reason());
        } catch (PeopleHomePayloadCodec.UnknownReference unknown) {
            write(response, HttpServletResponse.SC_NOT_FOUND,
                    "HOME_CONTRIBUTION_REFERENCE_NOT_FOUND");
        } catch (RuntimeException unavailable) {
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "PEOPLE_HOME_MATERIALIZATION_UNAVAILABLE");
        }
    }

    private static List<HomeMaterializationWorkloadProofV1.Header> headers(
            HttpServletRequest request) {
        List<HomeMaterializationWorkloadProofV1.Header> result = new ArrayList<>();
        var names = request.getHeaderNames();
        if (names == null) return List.of();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            for (String value : Collections.list(request.getHeaders(name))) {
                result.add(new HomeMaterializationWorkloadProofV1.Header(name, value));
            }
        }
        return List.copyOf(result);
    }

    private void write(HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        mapper.writeValue(response.getOutputStream(), java.util.Map.of("code", code));
    }
}
