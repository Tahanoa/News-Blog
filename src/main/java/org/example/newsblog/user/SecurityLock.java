package org.example.newsblog.user;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "security_lock")
class SecurityLock {
    @Id private Integer id;
    protected SecurityLock() {}
}
