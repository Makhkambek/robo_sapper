# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Workflow Instructions

**Если задач несколько или они сложные:**
1. Сначала создай TODO list с конкретными шагами перед выполнением
2. Думай вслух — объясняй что делаешь и почему, чтобы не ошибиться
3. Выполняй шаги по одному, проверяя каждый перед следующим

**Когда пользователь говорит "проверь все на ошибки", "найди баги", "check for bugs", "что может быть не так" — запускай `/logic-audit`:**
1. Проверяй знаки в формулах и направления моторов — самые частые скрытые баги
2. Сверяй константы с физической реальностью (правильные единицы? разумный диапазон?)
3. Аудит state machine: все состояния достижимы? есть выход из каждого?
4. Fallback-логика: реально ли срабатывает, или Vision всегда побеждает?
5. Репортируй: 🔴 CRITICAL / 🟡 SUSPICIOUS / 🟢 WORTH CHECKING

**Когда пользователь говорит "перепроверь всё", "verify everything", "double check" — запускай `/data-flow-audit`:**
1. Для каждого ключевого значения (дистанция, координаты, velocity) строй явную цепочку: `источник → метод A → метод B → конечное использование`
2. Находи похожие методы и сравнивай — применяют ли они одинаковые трансформации
3. Проверяй единицы на каждой границе: метры/дюймы/мм, raw/corrected, ticks/degrees/radians
4. Репортируй ✅/❌ для каждого метода — не "выглядит норм", а явный структурированный отчёт

## Project Overview

FTC (FIRST Tech Challenge) robotics project for Team 25444 - The Reckless, competing in the Houston regional 2024-2025 season. Автономный турельный shooter с AprilTag vision, Pedro Pathing odometry, и гибридным auto/teleop управлением.

**Key Technologies:**
- FTC SDK 11.0
- Pedro Pathing 2.0.5 (odometry + path following)
- GoBilda Pinpoint odometry driver (configured in **INCH**)
- Limelight 3A (AprilTag vision, iBUS-like API via FiducialResult)
- Bulk I2C reads (MANUAL cache mode)

## Build Commands

```bash
# Build debug APK (from root directory)
./gradlew assembleDebug

# Clean build
./gradlew clean assembleDebug

# Install to connected robot controller
./gradlew installDebug
```

## Running on Robot

Deploy APK via Android Studio or `gradlew installDebug`. On Driver Station, select OpMode:

**TeleOp (alliance-specific):**
- **RED Alliance TeleOp** (AprilTag ID 24)
- **BLUE Alliance TeleOp** (AprilTag ID 20)

**Autonomous (4 variants):**
- `Blue AutoClose` / `Blue AutoFar`
- `Red AutoClose` / `Red AutoFar`
- `preselectTeleOp` автоматически подхватывает соответствующий TeleOp

**TeleOp mode selection** (dpad на gamepad1 в Init фазе ДО нажатия Start):
- **Dpad Up** → NORMAL (auto-aim + vision + shooting)
- **Dpad Left** → NO_AUTO (NORMAL + fixed start pose если Auto не запускался)
- **Dpad Down** → EMERGENCY (turret manual only, без auto-aim)
- **Right Bumper** → confirm selection (опционально)
- **Start** → init Robot, но моторы НЕ запускаются

**Driver-ready gating:** После Start турель и flywheel *не запускаются* пока водитель не тронет любой джойстик (deadzone 0.1). Это даёт ~3 секунды тишины между Auto→TeleOp для regulations. Как только тронул стик — вызывается `robot.activateDriver()` и auto-aim + flywheel стартуют.

## Architecture Overview

### Three-Layer Design

**Layer 1: SubSystems** (`SubSystems/`) — Hardware abstraction
- `Robot.java` — master orchestrator, владеет всеми подсистемами и controllers
- `DriveTrain.java`, `Intake.java`, `Shooter.java`, `Turret.java`, `Vision.java`, `Localizer.java`

**Layer 2: Controllers** (`Controllers/`) — Business logic + gamepad input
- `TurretController`, `ShooterController`, `IntakeController`, `ResetController`

**Layer 3: OpModes** (`OpModes/`) — Entry points
- `RedAllianceTeleOp.java` / `BlueAllianceTeleOp.java`
- `BlueAutoClose.java` / `BlueAutoFar.java` / `RedAutoClose.java` / `RedAutoFar.java`
- `OpModes/auto/AutoHelper.java` — shared auto sequences
- `OpModes/Testers/` — individual subsystem testers

### Key Architectural Patterns

**Singleton Localizer** — persists across Auto→TeleOp:
```java
Localizer localizer = Localizer.getInstance(hardwareMap);  // init
Localizer.getInstance();                                    // reuse
```
Все координаты в **дюймах**, heading в **градусах**. `setPosition(x, y, heading)` вызывается из Auto `stop()` и передаёт финальную позу в TeleOp singleton.

**Auto→TeleOp pose handoff:**
1. Каждый Auto opmode в `stop()` вызывает `localizer.setPosition(follower.getPose().x, .y, Math.toDegrees(heading))` **до** `follower.breakFollowing()`
2. Работает даже при раннем прерывании (таймер, ручной stop, краш) — сохраняется **реальная позиция** где робот сейчас находится
3. TeleOp читает singleton при init: если `|x|>1 или |y|>1` → использует её, иначе дефолт

**Multiple Turret constructors** — в зависимости от контекста:
- `Turret(hw, vision, localizer)` — Auto, одометрия через Localizer
- `Turret(hw, vision, follower)` — TeleOp, одометрия через Pedro Follower. **НЕ ресетит encoder** (сохраняет позицию турели из Auto).
- `Turret(hw, vision)` — Vision-only тестирование
- `Turret(hw)` — базовый тест без всего

## Critical Subsystem Details

### Turret (PIDF + Vision + Physics)

**Auto-Aim приоритет:**
1. **Vision** (highest): `vision.getTargetYaw()` + EMA smoothing зависящий от дистанции (`emaFactor = 0.90 - 0.25*(dist-40)/55`). Ближе = быстрее, дальше = плавнее.
2. **Physics-based shot** (always active when goal set): iterative virtual target для shoot-on-move. При vel=0 совпадает с одометрией.
3. **Odometry fallback** (`calculateTargetAngle`): field→robot coordinate transform через heading, `atan2`.
4. **Manual** (lowest): right stick обновляет `targetAngle` инкрементально.

**Калибровка:**
- `TICKS_PER_DEGREE = 3.15`
- PIDF: `kP=0.040, kI=0.0, kD=0.001, kF=0.09`
- `kF` применяется как **static friction feedforward**: `kF * signum(error)` когда `|error| > ANGLE_TOLERANCE` (2°)
- Motor direction: `REVERSE` → right = positive angles
- **Range: MIN=-180°, MAX=+120°** (300° total)

**Важно:** Vision yaw EMA формула в `autoAimLegacy()` line ~511. Если турель "ползёт" медленно к цели — подними базовый коэффициент (сейчас 0.90).

### Shooter (FSM + Flywheel PIDF)

**State Machine:**
```
IDLE → OPEN_STOP (0.06s) → FEED (1.0s) → RESET → IDLE
```

**Velocity управление:**
- SDK PIDF на firmware уровне: `PIDF_P=100, PIDF_F=14, I=0, D=0`
- **Полиномиальная формула** (`y = A*d³ + B*d² + C*d + D`, tuned empirically):
  - `VELOCITY_B = -0.000277358`
  - `VELOCITY_C =  0.0768308`
  - `VELOCITY_D =  0.0651042`
  - `VELOCITY_E =  984.83603`
- Fallback velocity когда нет дистанции: `1300 ticks/sec`
- Velocity clamp: `[0, 1700]`
- Active braking: если `currentVel > target + DECEL_THRESHOLD(50)` → команда `target - DECEL_BOOST(300)` чтобы firmware тормозил быстрее

**Hood (сервопривод):**
- Логистическая формула: `y = L / (1 + exp(-(k*x - x0)))` with `L=0.606, k=0.0727, x0=3.646`
- Hood range: `[0.0, 0.6]`
- EMA smoothing `HOOD_SMOOTHING = 0.6` (агрессивно — кандидат на снижение если jitter)

**Критично:** всегда вызывай `shooter.updatePID()` в основном цикле — поддерживает velocity tracking даже когда не стреляем.

### Vision (Limelight 3A AprilTag)

**Alliance tags:**
- RED = 24
- BLUE = 20

**API:**
- `hasTargetTag()` — persistence filter, требует 3 последовательных кадра для стабилизации (устраняет flickering на дальних дистанциях)
- `getTargetTag()` — возвращает synthetic `AprilTagDetection` из Limelight `FiducialResult`
- `getTargetDistance()` — 2-point linear calibration: `real = (raw - DISTANCE_OFFSET) * DISTANCE_SCALE` where `OFFSET=23.3, SCALE=1.074`
- `getTargetYaw()` — `tx` от Limelight в градусах
- Cache validity: 20ms (50Hz)

**Disconnect detection:** если `result.getStaleness() > 500ms` → считаем камеру отключенной.

### Robot (Master Orchestrator)

**Initialization order в конструкторе (CRITICAL):**
1. Bulk read setup (`MANUAL` mode)
2. Pedro Follower (`Constants.createFollower(hw)` + `follower.update()` для init Pinpoint)
3. `Localizer.getInstance(hw)` singleton
4. Vision (init + start + setAlliance)
5. DriveTrain, Intake, Shooter, Turret (Turret зависит от Vision + Follower)
6. Controllers (gamepad inject через `null` в конструкторе, реальный gamepad передаётся через `update()`)
7. Mode-specific init (EMERGENCY → `disableAutoAim()`)

**Goal и Tag координаты (в Robot.java как static final):**
- `RED_GOAL = (144, 144)`, `BLUE_GOAL = (0, 144)`
- `RED_TAG = (128, 131)`, `BLUE_TAG = (16, 132)`

Goal используется для расчёта `calculateTargetAngle()` турели. Tag используется для distance (velocity/hood формулы калиброваны от тега, не от basket — разница ~16 дюймов).

**`start()`** — минимальный init, только `intake.off()`. Моторы ждут `activateDriver()`.

**`activateDriver()`** — вызывается при первом вводе джойстика в TeleOp loop. Запускает turret auto-aim и flywheel spin-up с velocity для текущей дистанции до тега.

**`update(gp1, gp2, telemetry)`** основной loop:
1. Loop timing (avgLoopMs EMA)
2. `clearBulkCache()` — все последующие I2C reads из кеша
3. `follower.update()`
4. `driveTrain.drive(gp1)` — field-centric mecanum
5. Distance to TAG (odometry) с spinning detection (freeze distance если heading крутится быстро)
6. Vision distance (если тег виден) — override odometry
7. Physics virtual distance (если `hasPhysicsShot()`)
8. Update shooter velocity/hood через effective distance
9. `shooter.updatePID()`
10. `updateControllers()` — intake / shooter / turret controllers
11. Telemetry (каждый 3-й loop для производительности)

### Localizer (Singleton Pinpoint Wrapper)

**Единицы: INCH для x/y, DEGREES для heading.** (Ранее были MM — не вернуть обратно!)

**Методы:**
- `update()` — обновляет x, y, heading (вызывать раз в loop)
- `updateHeadingOnly()` — лёгкий update только heading для DriveController
- `setPosition(x, y, heading)` — Auto→TeleOp handoff
- `reset()` — сбрасывает внутреннее состояние (но не Pinpoint hardware — follower владеет им)

**Known latent bug:** heading wraparound в `update()` line 52. Delta не нормализуется перед накоплением. Проявляется только при полном 360° обороте за один loop (~20ms). Fix = 3 строки `while delta > 180 -= 360` перед `heading += delta`.

### DriveTrain

Mecanum drive с field-centric управлением. Left stick = X/Y движение, right stick = rotation. Heading lock через left bumper toggle.

## Hardware Configuration Names

**Motors:**
- `turretMotor` — turret rotation (DcMotorEx, REVERSE direction)
- `shooterMotor1`, `shooterMotor2` — dual flywheel
- `Intake` — ball collection
- `leftFront`, `rightFront`, `leftRear`, `rightRear` — mecanum drive

**Servos:**
- `shooterHood` — angle 0.0-0.6
- `shooterStop` — ball gate 0.05 (closed) / 0.29 (open)
- `intakeStop` — intake gate 0.9 (shooting) / 1.0 (normal)

**Sensors:**
- `limelight` — Limelight 3A AprilTag camera
- `pinpoint` — GoBilda Pinpoint odometry

## Pedro Pathing Constants (текущие значения)

**FollowerConstants:**
- `forwardZeroPowerAcceleration = -29.7`
- `lateralZeroPowerAcceleration = -74.43` ⚠️ 2.5× forward — асимметрия, проверить замерами
- `translationalPIDF = (0.015, 0, 0.0001, 0.015)`
- `headingPIDF = (1.95, 0, 0.05, 0.013)`
- `drivePIDF = (0.7, 0, 0.01, 0.6, 0.04)` (FilteredPIDF velocity controller)
- `centripetalScaling = 0.00058`
- `mass = 12.2` (проверить реальный вес робота)

**MecanumConstants:**
- `xVelocity = 92.8` in/s
- `yVelocity = 71` in/s ⚠️ 23% меньше x — проверить замерами
- Left side motors REVERSED

**PinpointConstants:**
- `forwardPodY = -4.9` in
- `strafePodX = 0` in
- `distanceUnit = INCH`
- Both encoders REVERSED
- Pod type: `goBILDA_4_BAR_POD`

**PathConstraints:** `(0.99, 100, 0.8, 1)` = `(tValueConstraint, timeoutMs, brakingStrength, brakingStart)`

## Emergency Reset System

`ResetController` на Options button (gamepad2):
1. Re-enable turret auto-aim
2. `intake.off()`
3. `shooter.reset()` — FSM → IDLE
4. `turret.returnToCenter()` — PID-controlled возврат к 0°

Non-blocking — другие операции продолжаются.

## Testing Strategy

**Testers** (`OpModes/Testers/`):
- Motor direction tester
- Servo tester
- Vision (Limelight) tester
- Turret PIDF tuner
- Shooter PIDF tuner
- Shooter FSM tester

**Order для новой сборки:**
1. Motor directions (single motor)
2. Servo ranges и safe positions
3. Limelight tag detection + distance calibration
4. Turret PIDF tuning (в режиме OVERRIDE чтобы PID не сопротивлялся)
5. Shooter velocity PIDF
6. Shooter FSM timing
7. Pedro Pathing tuners (forward/lateral velocity + zero-power acceleration)
8. Full integration в TeleOp

## Common Gotchas

**Pinpoint reset:** НЕ вызывай `pinpoint.resetPosAndIMU()` в Localizer конструкторе — follower уже инициализировал hardware. Reset сломает follower state.

**Turret encoder в TeleOp:** конструктор с Follower **не ресетит** encoder (сохраняет позицию из Auto). При fresh power-on в NO_AUTO mode encoder = 0 даже если турель физически в другом положении — нужна физическая калибровка или hard limit switch перед запуском.

**Heading wraparound:** Localizer не нормализует delta перед накоплением — работает только пока робот не делает полных 360° оборотов за один loop.

**Vision + spinning:** при быстром повороте робота Robot.update() "морозит" distanceToGoal на последнем стабильном значении (`SPIN_THRESHOLD_DEG_PER_FRAME = 0.5`) — Pinpoint pods не в центре вращения → фейковый drift.

**Telemetry rate:** обновляется каждый 3-й loop (~17Hz если основной loop 50Hz). Если нужно видеть быстрые изменения — временно поставь `loopCount % 1`.

## Git Workflow

- `main` — stable competition code
- `dev` — development
- Feature branches для больших изменений

## Java Code Conventions

**Naming:**
- `camelCase` для методов и полей
- `PascalCase` для классов
- `SCREAMING_SNAKE_CASE` для констант

**Patterns:**
- FSM для всех длинных операций (пример: Shooter)
- НЕ используй `sleep()` или `Thread.sleep()` в OpMode loop
- Manual control через `targetAngle` + PID, а не прямой `setPower()`

**Comments:** проект смешанный русский/английский. Стандарт: русский для инлайн пояснений, английский для публичного API / констант.

## File Organization

```
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/
├── Controllers/                  # Business logic layer
│   ├── IntakeController.java
│   ├── ResetController.java
│   ├── ShooterController.java
│   └── TurretController.java
├── SubSystems/                   # Hardware abstraction layer
│   ├── Robot.java                # Master orchestrator
│   ├── DriveTrain.java           # Mecanum
│   ├── Intake.java
│   ├── Shooter.java              # Flywheel + Hood FSM + PIDF
│   ├── ShooterConstants.java     # Physics constants
│   ├── Turret.java               # 930 lines — god class, refactor TODO
│   ├── Vision.java               # Limelight 3A wrapper
│   └── Localizer.java            # Pinpoint singleton (INCH)
├── OpModes/
│   ├── RedAllianceTeleOp.java
│   ├── BlueAllianceTeleOp.java
│   ├── BlueAutoClose.java
│   ├── BlueAutoFar.java
│   ├── RedAutoClose.java
│   ├── RedAutoFar.java           # @Disabled — решить судьбу
│   ├── TeleOpMode.java           # enum NORMAL/NO_AUTO/EMERGENCY
│   ├── test.java
│   ├── auto/
│   │   └── AutoHelper.java       # shared auto sequences
│   └── Testers/                  # individual subsystem tests
└── pedroPathing/
    ├── Constants.java
    └── Tuning.java
```

## Known Technical Debt (Postseason Cleanup)

1. **Turret.java 930 строк** — разбить на `TurretMotor` / `TurretAimer` / `TurretBallistics` / `Turret` facade
2. **4 Auto файла дублируются на 80%** — свести в `AllianceAutoBase`
3. **2 TeleOp файла дублируются** — свести в `AllianceTeleOpBase`
4. **Магические числа** (field coords) → вынести в `FieldConstants.java`
5. **Dead reloc code** в Robot.java (`relocLossFrames`, `relocFreezeFrames`, `VISION_CORRECTION_WEIGHT`) — удалить
6. **Shooter velocity comment** врёт про "linear" — поправить
7. **Dashboard PIDF_I/PIDF_D** в Shooter объявлены @Config но всегда хардкод 0 — либо использовать, либо убрать @Config
8. **Heading wraparound** в Localizer — 3-строчный фикс
9. **Commented-out telemetry** в TeleOp файлах — удалить или вынести в debug mode
