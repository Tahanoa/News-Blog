package org.example.newsblog.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("ai-local")
@RequestMapping("/api/admin/ai")
class AiTextController {
    private final AiTextService service;

    AiTextController(AiTextService service) {
        this.service = service;
    }

    @GetMapping("/health")
    AiTextService.Health health() {
        return service.health();
    }

    @PostMapping("/generate")
    AiTextService.Generation generate(@Valid @RequestBody GenerateRequest request) {
        return service.generate(request.prompt());
    }

    @ExceptionHandler(AiTextService.AiException.class)
    ResponseEntity<ApiError> aiError(AiTextService.AiException exception) {
        return ResponseEntity.status(exception.status).body(new ApiError(exception.code, exception.getMessage()));
    }

    record GenerateRequest(@NotBlank @Size(max = 12000) String prompt) {}
    record ApiError(String code, String message) {}

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> invalidInput() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT",
                "بدنه JSON باید شامل prompt غیرخالی با حداکثر ۱۲۰۰۰ نویسه باشد."));
    }
}
