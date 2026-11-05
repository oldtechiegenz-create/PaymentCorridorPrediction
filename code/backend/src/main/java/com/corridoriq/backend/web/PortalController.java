package com.corridoriq.backend.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.corridoriq.backend.config.CorridorIqProperties;

/** Serves the single-page portal (corridoriq.html at the repository root) at {@code /}. */
@RestController
public class PortalController {

    /** UTF-8 must be stated: without it browsers decode the page as Windows-1252 and garble "·", "→", "★". */
    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final Path portal;

    public PortalController(CorridorIqProperties props) {
        this.portal = props.portalPath();
    }

    @GetMapping("/")
    public ResponseEntity<Resource> portal() {
        if (!Files.isRegularFile(portal)) {
            throw ApiException.notFound("Portal not found at " + portal);
        }
        return ResponseEntity.ok().contentType(HTML_UTF8).body(new FileSystemResource(portal));
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }
}
