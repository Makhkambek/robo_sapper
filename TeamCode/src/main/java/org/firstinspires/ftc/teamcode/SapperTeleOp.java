/*
 * SAPPER ROBOT — FTC TeleOp (REV Control Hub + Logitech gamepad)
 *
 * HARDWARE (Control Hub config — names must match exactly):
 *
 *   Motors (4, tank drive; left pair identical, right pair identical):
 *     Motor 0 — leftFront
 *     Motor 1 — leftBack
 *     Motor 2 — rightFront
 *     Motor 3 — rightBack
 *
 *   Servos (6 physical servos on 6 Control Hub ports):
 *     Servo 0 — shoulderA
 *     Servo 1 — shoulderB   (mounted same orientation as A → no reverse)
 *     Servo 2 — bicepA
 *     Servo 3 — bicepB      (mounted same orientation as A → no reverse)
 *     Servo 4 — wrist       (single servo)
 *     Servo 5 — grabber
 *
 * CONTROLS (gamepad1, tank drive — arcade mixing):
 *     Left stick Y           → forward / backward
 *     Left stick X           → turn
 *     Right stick X          → turn (duplicate)
 *     Mix: L = Y + X, R = Y − X (normalized if |power| > 1)
 *     Right Trigger          → SLOW MODE (half speed while held)
 *
 *     Dpad UP                → ARM preset UP
 *     Dpad DOWN              → ARM preset DOWN
 *     Dpad RIGHT             → ARM preset MIDDLE
 *
 *     Left Bumper (LB)       → GRABBER toggle (press = flip open/closed)
 *
 * SERVO POSITIONS are defined as constants below — tune after mounting.
 * Servo position is 0.0 (= 500 µs) to 1.0 (= 2500 µs).
 */

package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;

@TeleOp(name = "Sapper", group = "Sapper")
public class SapperTeleOp extends LinearOpMode {

    // ============= HARDWARE =============
    private DcMotor leftFront, leftBack, rightFront, rightBack;
    private Servo shoulderA, shoulderB, bicepA, bicepB, wrist, grabber;

    // ============= SERVO PRESETS (0.0 … 1.0) =============
    // Values derived from the Arduino Sapper preset table — tune these after
    // physically mounting the arm. All servos use 500–2500 µs range, so
    // 0.0 = 500 µs, 0.5 = 1500 µs (center), 1.0 = 2500 µs.

    // ARM UP — calibrate after physical mounting
    private static final double SHOULDER_UP = 0.70;
    private static final double BICEP_A_UP  = 0.33;
    private static final double BICEP_B_UP  = 0.30;
    private static final double WRIST_UP    = 1.0;

    // ARM MIDDLE (Dpad RIGHT)
    private static final double SHOULDER_MID = 0.30;
    private static final double BICEP_A_MID  = 0.23;
    private static final double BICEP_B_MID  = 0.20;
    private static final double WRIST_MID    = 1.0;

    // ARM DOWN
    private static final double SHOULDER_DOWN = 0.00;
    private static final double BICEP_A_DOWN  = 0.03;
    private static final double BICEP_B_DOWN  = 0.00;
    private static final double WRIST_DOWN    = 1.0;

    // GRABBER
    private static final double GRABBER_OPEN   = 0.55;
    private static final double GRABBER_CLOSED = 1;

    // ============= INPUT =============
    private static final double STICK_DEADZONE    = 0.05;
    private static final double SLOW_MULTIPLIER   = 0.5;   // speed factor when RT held
    private static final double SLOW_THRESHOLD    = 0.3;   // how far to press RT to trigger

    // ============= STATE =============
    private boolean grabberClosed = false;
    private boolean prevLB = false;
    private double bicepAGoal = BICEP_A_MID;
    private double bicepBGoal = BICEP_B_MID;

    // ============= LIFECYCLE =============
    @Override
    public void runOpMode() {
        initHardware();

        // Safe starting pose
        applyArm(SHOULDER_MID, BICEP_A_MID, BICEP_B_MID, WRIST_MID);
        applyGrabber(false);

        telemetry.addLine("Sapper ready — press PLAY");
        telemetry.addLine("Dpad UP/RIGHT/DOWN = arm UP/MID/DOWN");
        telemetry.addLine("LB = grabber toggle   RT = slow mode");
        telemetry.update();

        waitForStart();
        if (isStopRequested()) return;

        while (opModeIsActive()) {
            drive();
            armControl();
            grabberControl();
            publishTelemetry();
        }
    }

    // ============= INIT =============
    private void initHardware() {
        leftFront  = hardwareMap.get(DcMotor.class, "leftFront");
        leftBack   = hardwareMap.get(DcMotor.class, "leftBack");
        rightFront = hardwareMap.get(DcMotor.class, "rightFront");
        rightBack  = hardwareMap.get(DcMotor.class, "rightBack");

        // Right side is mirrored on the chassis → reverse in code.
        rightFront.setDirection(DcMotorSimple.Direction.FORWARD);
        rightBack.setDirection(DcMotorSimple.Direction.REVERSE);

        DcMotor[] motors = { leftFront, leftBack, rightFront, rightBack };
        for (DcMotor m : motors) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }

        shoulderA = hardwareMap.get(Servo.class, "shoulderA");
        shoulderB = hardwareMap.get(Servo.class, "shoulderB");
        bicepA    = hardwareMap.get(Servo.class, "bicepA");
        bicepB    = hardwareMap.get(Servo.class, "bicepB");
        wrist     = hardwareMap.get(Servo.class, "wrist");
        grabber   = hardwareMap.get(Servo.class, "grabber");

        // B-servos mounted mirrored → REVERSE so the same logical preset value
        // drives both physical servos to move together (one raw 0.8, other raw 0.2).
        // If в итоге наоборот — поменяй местами REVERSE с A-серво (shoulderA вместо shoulderB).
        shoulderB.setDirection(Servo.Direction.REVERSE);
        bicepB.setDirection(Servo.Direction.REVERSE);
    }

    // ============= DRIVE =============
    private void drive() {
        // FTC convention: gamepad stick Y is negative when pushed forward.
        double forward = -applyDeadzone(gamepad1.left_stick_y);
        double turn    =  applyDeadzone(gamepad1.left_stick_x)
                        + applyDeadzone(gamepad1.right_stick_x);

        double left  = forward + turn;
        double right = forward - turn;

        // Normalize so neither side saturates past ±1.
        double max = Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
        left  /= max;
        right /= max;

        // Slow mode — hold RT for half speed.
        if (gamepad1.right_trigger > SLOW_THRESHOLD) {
            left  *= SLOW_MULTIPLIER;
            right *= SLOW_MULTIPLIER;
        }

        leftFront.setPower(left);
        leftBack.setPower(left);
        rightFront.setPower(right);
        rightBack.setPower(right);
    }

    private double applyDeadzone(double v) {
        return Math.abs(v) < STICK_DEADZONE ? 0.0 : v;
    }

    // ============= ARM =============
    private void armControl() {
        if (gamepad1.dpad_up) {
            applyArm(SHOULDER_UP, BICEP_A_UP, BICEP_B_UP, WRIST_UP);
        } else if (gamepad1.dpad_down) {
            applyArm(SHOULDER_DOWN, BICEP_A_DOWN, BICEP_B_DOWN, WRIST_DOWN);
        } else if (gamepad1.dpad_right) {
            applyArm(SHOULDER_MID, BICEP_A_MID, BICEP_B_MID, WRIST_MID);
        }
    }

    private void applyArm(double shoulderPos, double bicepAPos, double bicepBPos, double wristPos) {
        shoulderA.setPosition(shoulderPos);
        shoulderB.setPosition(shoulderPos);   // direction=REVERSE handles mirror
        bicepA.setPosition(bicepAPos);
        bicepB.setPosition(bicepBPos);        // direction=REVERSE handles mirror; tune B independently to compensate mounting offset
        bicepAGoal = bicepAPos;
        bicepBGoal = bicepBPos;
        wrist.setPosition(wristPos);
    }

    // ============= GRABBER =============
    private void grabberControl() {
        boolean lb = gamepad1.left_bumper;
        if (lb && !prevLB) {                  // rising edge = toggle
            grabberClosed = !grabberClosed;
            applyGrabber(grabberClosed);
        }
        prevLB = lb;
    }

    private void applyGrabber(boolean closed) {
        grabber.setPosition(closed ? GRABBER_CLOSED : GRABBER_OPEN);
    }

    // ============= TELEMETRY =============
    private void publishTelemetry() {
        boolean slow = gamepad1.right_trigger > SLOW_THRESHOLD;
        telemetry.addData("drive L/R",
                "%.2f / %.2f%s", leftFront.getPower(), rightFront.getPower(),
                slow ? "  [SLOW]" : "");
        telemetry.addData("grabber", grabberClosed ? "CLOSED" : "OPEN");
        telemetry.addData("shoulder", "%.2f", shoulderA.getPosition());
        telemetry.addData("bicepA", "goal=%.3f  cur=%.3f", bicepAGoal, bicepA.getPosition());
        telemetry.addData("bicepB", "goal=%.3f  cur=%.3f", bicepBGoal, bicepB.getPosition());
        telemetry.addData("wrist", "%.2f", wrist.getPosition());
        telemetry.update();
    }
}
