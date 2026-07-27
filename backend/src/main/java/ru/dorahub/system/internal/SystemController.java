package ru.dorahub.system.internal;

import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

  private final Clock clock;

  public SystemController(Clock clock) {
    this.clock = clock;
  }

  @GetMapping("/time")
  public ServerTimeResponse time() {
    return new ServerTimeResponse(clock.instant());
  }

  public record ServerTimeResponse(Instant serverTime) {}
}
