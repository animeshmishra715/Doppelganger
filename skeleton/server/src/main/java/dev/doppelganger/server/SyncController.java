package dev.doppelganger.server;

import dev.doppelganger.core.LogEntry;
import dev.doppelganger.core.Operation;
import dev.doppelganger.core.PushResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    public record PushRequest(List<Operation> ops) {
    }

    public record PushResponse(List<String> accepted, long head) {
    }

    public record PullResponse(List<LogEntry> entries, long head) {
    }

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    /** Device sends its outbox, in order. Resending the same operations is safe. */
    @PostMapping("/push")
    public PushResponse push(@RequestBody PushRequest request) {
        if (request == null || request.ops() == null) {
            throw new IllegalArgumentException("Body must be {\"ops\": [...]}");
        }
        PushResult result = sync.push(request.ops());
        return new PushResponse(result.accepted(), result.head());
    }

    /** All log entries after the given log position. */
    @GetMapping("/pull")
    public PullResponse pull(@RequestParam(defaultValue = "0") long since) {
        return new PullResponse(sync.pull(since), sync.head());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
