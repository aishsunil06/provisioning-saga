package dev.aishsunil.provisioning.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One line in the pool, either FREE or HELD by exactly one reservation. */
@Entity
@Table(name = "lines")
public class Line {

    public static final String FREE = "FREE";

    public static final String HELD = "HELD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String msisdn;

    @Column(name = "line_type", nullable = false)
    private String lineType;

    @Column(nullable = false)
    private String state;

    protected Line() {
    }

    public Long getId() {
        return this.id;
    }

    public String getMsisdn() {
        return this.msisdn;
    }

    public String getLineType() {
        return this.lineType;
    }

    public String getState() {
        return this.state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
