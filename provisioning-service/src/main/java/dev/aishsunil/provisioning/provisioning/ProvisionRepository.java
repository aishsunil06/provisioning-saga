package dev.aishsunil.provisioning.provisioning;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProvisionRepository extends JpaRepository<Provision, UUID> {

}
