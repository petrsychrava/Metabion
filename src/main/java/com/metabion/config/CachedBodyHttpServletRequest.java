package com.metabion.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

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

    private Charset charset() {
        var encoding = getCharacterEncoding();
        return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    }
}
