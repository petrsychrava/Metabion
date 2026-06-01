package com.metabion.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private Map<String, String[]> parameterMap;

    CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    @Override
    public ServletInputStream getInputStream() {
        var input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }

            @Override
            public int read() {
                return input.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    byte[] body() {
        return body.clone();
    }

    @Override
    public String getParameter(String name) {
        var values = getParameterValues(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (!isFormUrlEncoded()) {
            return super.getParameterMap();
        }
        if (parameterMap == null) {
            parameterMap = parseParameters();
        }
        return parameterMap;
    }

    @Override
    public java.util.Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        return getParameterMap().get(name);
    }

    private Charset charset() {
        var encoding = getCharacterEncoding();
        return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    }

    private boolean isFormUrlEncoded() {
        var contentType = getContentType();
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private Map<String, String[]> parseParameters() {
        var parsed = new LinkedHashMap<String, ArrayList<String>>();
        parsePairs(getQueryString(), parsed);
        parsePairs(new String(body, charset()), parsed);
        super.getParameterMap().forEach((name, values) -> {
            if (parsed.containsKey(name)) {
                return;
            }
            var list = parsed.computeIfAbsent(name, ignored -> new ArrayList<>());
            Collections.addAll(list, values);
        });

        var result = new LinkedHashMap<String, String[]>();
        parsed.forEach((name, values) -> result.put(name, values.toArray(String[]::new)));
        return Collections.unmodifiableMap(result);
    }

    private void parsePairs(String source, Map<String, ArrayList<String>> parsed) {
        if (source == null || source.isBlank()) {
            return;
        }
        for (var pair : source.split("&")) {
            var parts = pair.split("=", 2);
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            var name = urlDecode(parts[0]);
            var value = parts.length == 2 ? urlDecode(parts[1]) : "";
            parsed.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, charset());
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
