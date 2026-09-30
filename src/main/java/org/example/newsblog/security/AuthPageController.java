package org.example.newsblog.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class AuthPageController {
    @GetMapping({"/", "/login", "/register"})
    String page() { return "forward:/index.html"; }
}
