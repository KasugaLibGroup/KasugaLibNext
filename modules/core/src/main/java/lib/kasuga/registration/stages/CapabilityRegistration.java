package lib.kasuga.registration.stages;

import lombok.Getter;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@Getter
public class CapabilityRegistration {
    protected RegisterCapabilitiesEvent event;
    public CapabilityRegistration(RegisterCapabilitiesEvent event) {
        this.event = event;
    }
}
