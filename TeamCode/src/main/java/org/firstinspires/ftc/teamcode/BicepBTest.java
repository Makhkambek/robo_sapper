package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.Servo;

@TeleOp(name = "BicepB Test", group = "Sapper")
public class BicepBTest extends LinearOpMode {

    @Override
    public void runOpMode() {
        Servo bicepB = hardwareMap.get(Servo.class, "bicepB");

        double cmd = 0.0;
        bicepB.setPosition(cmd);

        telemetry.addLine("BicepB test — Dpad UP = 0.5, Dpad DOWN = 0.0");
        telemetry.update();

        waitForStart();

        while (opModeIsActive()) {
            if (gamepad1.dpad_up)   cmd = 0.5;
            if (gamepad1.dpad_down) cmd = 0.0;

            bicepB.setPosition(cmd);

            telemetry.addData("cmd", "%.2f", cmd);
            telemetry.update();
        }
    }
}
