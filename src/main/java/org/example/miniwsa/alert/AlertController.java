package org.example.miniwsa.alert;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/alerts")
class AlertController {

    private final AlertRepository repository;
    private final AlertEvaluator  evaluator;

    AlertController(AlertRepository repository, AlertEvaluator evaluator) {
        this.repository = repository;
        this.evaluator  = evaluator;
    }

    @PostMapping("/define")
    @ResponseStatus(HttpStatus.CREATED)
    AlertRule define(@Valid @RequestBody AlertRuleRequest request) {
        return repository.save(request);
    }

    @GetMapping("/evaluate")
    List<AlertResult> evaluate() {
        return evaluator.evaluate();
    }
}
