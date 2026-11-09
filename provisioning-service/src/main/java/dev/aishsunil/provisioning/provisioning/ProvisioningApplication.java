package dev.aishsunil.provisioning.provisioning;

import dev.aishsunil.provisioning.provisioning.network.FailureProfile;
import dev.aishsunil.provisioning.provisioning.network.FakeNetworkAdapter;
import dev.aishsunil.provisioning.provisioning.network.NetworkAdapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(FailureProfile.class)
public class ProvisioningApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProvisioningApplication.class, args);
    }

    @Bean
    public NetworkAdapter networkAdapter(FailureProfile profile) {
        return new FakeNetworkAdapter(profile);
    }

}
