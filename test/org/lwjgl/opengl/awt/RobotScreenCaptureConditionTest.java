package org.lwjgl.opengl.awt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotScreenCaptureConditionTest {

    @Test
    void parsesScreenCaptureModes() {
        assertEquals(RobotScreenCaptureCondition.Mode.OFF,
                RobotScreenCaptureCondition.parseMode("off"));
        assertEquals(RobotScreenCaptureCondition.Mode.AUTO,
                RobotScreenCaptureCondition.parseMode(" Auto "));
        assertEquals(RobotScreenCaptureCondition.Mode.REQUIRED,
                RobotScreenCaptureCondition.parseMode("REQUIRED"));
        assertThrows(ExtensionConfigurationException.class,
                () -> RobotScreenCaptureCondition.parseMode("enabled"));
    }

    @Test
    void recognizesSystemsWhosePrivatePickerCannotBePreflighted() {
        assertFalse(RobotScreenCaptureCondition.hasPrivateWindowPicker("10.15.7"));
        assertFalse(RobotScreenCaptureCondition.hasPrivateWindowPicker("14.7.6"));
        assertTrue(RobotScreenCaptureCondition.hasPrivateWindowPicker("15.0"));
        assertTrue(RobotScreenCaptureCondition.hasPrivateWindowPicker("26.1"));
        assertTrue(RobotScreenCaptureCondition.hasPrivateWindowPicker("10.16"));
        assertTrue(RobotScreenCaptureCondition.hasPrivateWindowPicker("unknown"));
    }
}
