package convoy.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ConvoyPropertiesAwsTest {
    @Test
    void blankEndpointMeansRealAws() {
        assertNull(new ConvoyProperties.Aws("", "ap-southeast-2", "123456789012").endpoint());
        assertNull(new ConvoyProperties.Aws("  ", "ap-southeast-2", "123456789012").endpoint());
        assertEquals("http://localstack:4566", new ConvoyProperties.Aws("http://localstack:4566", "ap-south-1", null).endpoint());
    }
}
