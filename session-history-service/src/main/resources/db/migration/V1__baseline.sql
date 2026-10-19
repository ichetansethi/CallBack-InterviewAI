
CREATE TABLE public.feedback_summary (
    id uuid NOT NULL,
    clarity_score integer NOT NULL,
    created_at timestamp(6) with time zone,
    overall_summary text,
    structure_score integer NOT NULL,
    technical_depth_score integer NOT NULL,
    session_id uuid NOT NULL
);

CREATE TABLE public.interview_session (
    id uuid NOT NULL,
    ended_at timestamp(6) with time zone,
    owner_email character varying(255),
    question_set_id uuid,
    started_at timestamp(6) with time zone,
    status character varying(255),
    CONSTRAINT interview_session_status_check CHECK (((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'ABANDONED'::character varying])::text[])))
);

CREATE TABLE public.question_feedback (
    id uuid NOT NULL,
    feedback_text text,
    order_index integer NOT NULL,
    question_text text,
    summary_id uuid NOT NULL
);

CREATE TABLE public.transcript_turn (
    id uuid NOT NULL,
    question_rationale text,
    speaker character varying(255),
    text text,
    turn_index integer NOT NULL,
    session_id uuid,
    CONSTRAINT transcript_turn_speaker_check CHECK (((speaker)::text = ANY ((ARRAY['CANDIDATE'::character varying, 'COACH'::character varying])::text[])))
);

ALTER TABLE ONLY public.feedback_summary
    ADD CONSTRAINT feedback_summary_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.interview_session
    ADD CONSTRAINT interview_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.question_feedback
    ADD CONSTRAINT question_feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.transcript_turn
    ADD CONSTRAINT transcript_turn_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_summary
    ADD CONSTRAINT uktaffcovdr0wylpf8vw4phjgnt UNIQUE (session_id);

ALTER TABLE ONLY public.transcript_turn
    ADD CONSTRAINT fk1yfdggutm1c3xkv2kj32s60x8 FOREIGN KEY (session_id) REFERENCES public.interview_session(id);

ALTER TABLE ONLY public.feedback_summary
    ADD CONSTRAINT fkc1t1uq5aykjdcesnoqvku50th FOREIGN KEY (session_id) REFERENCES public.interview_session(id);

ALTER TABLE ONLY public.question_feedback
    ADD CONSTRAINT fkef2nr2ep2yh4b7s4v4mx6q6ek FOREIGN KEY (summary_id) REFERENCES public.feedback_summary(id);

