CREATE TABLE IF NOT EXISTS teacher
(
    id        BIGSERIAL PRIMARY KEY,
    reference VARCHAR(20)  NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    email     VARCHAR(150) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS student
(
    id        BIGSERIAL PRIMARY KEY,
    reference VARCHAR(20)  NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    email     VARCHAR(150) NOT NULL UNIQUE,
    phone     VARCHAR(20),
    cohort    VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS exercise
(
    id          BIGSERIAL PRIMARY KEY,
    teacher_id  BIGINT       NOT NULL REFERENCES teacher (id),
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    max_points  NUMERIC(5, 2) NOT NULL DEFAULT 20,
    deadline    DATE,
    -- timed exercise: the student must submit within this many minutes after opening it
    duration_minutes INTEGER CHECK (duration_minutes > 0),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    -- an exercise needs a deadline, a duration, or both
    CHECK (deadline IS NOT NULL OR duration_minutes IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS submission
(
    id           BIGSERIAL PRIMARY KEY,
    exercise_id  BIGINT    NOT NULL REFERENCES exercise (id),
    student_id   BIGINT    NOT NULL REFERENCES student (id),
    content      TEXT,
    repo_url     VARCHAR(300),
    submitted_at TIMESTAMP NOT NULL DEFAULT now(),
    raw_points   NUMERIC(5, 2),
    feedback     TEXT,
    graded_at    TIMESTAMP,
    UNIQUE (exercise_id, student_id)
);

-- When a student opened a timed exercise (start of their countdown)
CREATE TABLE IF NOT EXISTS exercise_start
(
    id          BIGSERIAL PRIMARY KEY,
    exercise_id BIGINT    NOT NULL REFERENCES exercise (id),
    student_id  BIGINT    NOT NULL REFERENCES student (id),
    -- no DEFAULT now(): set by the application, with the same clock as submission.submitted_at
    started_at  TIMESTAMP NOT NULL,
    UNIQUE (exercise_id, student_id)
);
