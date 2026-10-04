package com.arbitrator.server.config;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/** Keep existing 4xx guidance, but never render internal exception details on 5xx. */
@Component
public class SafeErrorAttributes extends DefaultErrorAttributes {
    public static final String PUBLIC_MESSAGE = "Unable to complete this request";
    private static final Logger log = LoggerFactory.getLogger(SafeErrorAttributes.class);

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        Map<String, Object> values = super.getErrorAttributes(request, options);
        Object status = values.get("status");
        if (status instanceof Number code && code.intValue() >= 500) {
            Object current = request.getAttribute(SecurityHeadersFilter.REQUEST_ID_ATTRIBUTE,
                    RequestAttributes.SCOPE_REQUEST);
            String id = current instanceof String value ? value
                    : request instanceof ServletWebRequest servlet
                            ? SecurityHeadersFilter.requestId(servlet.getRequest()) : "unavailable";
            Throwable error = getError(request);
            log.error("Request {} failed with HTTP {} ({})", id, code.intValue(),
                    error == null ? "Unknown" : error.getClass().getSimpleName());
            values.put("message", PUBLIC_MESSAGE);
            values.put("requestId", id);
            values.remove("trace");
            values.remove("exception");
            values.remove("errors");
            values.remove("path");
        }
        return values;
    }
}
