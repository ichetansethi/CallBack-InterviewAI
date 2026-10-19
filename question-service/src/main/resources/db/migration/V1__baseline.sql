
CREATE TABLE public.interview_question (
    id uuid NOT NULL,
    category character varying(255),
    order_index integer NOT NULL,
    question_text text,
    rationale text,
    question_set_id uuid
);

CREATE TABLE public.question_set (
    id uuid NOT NULL,
    compatibility_analysis_id uuid,
    created_at timestamp(6) with time zone,
    jd_id uuid,
    owner_email character varying(255)
);

ALTER TABLE ONLY public.interview_question
    ADD CONSTRAINT interview_question_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.question_set
    ADD CONSTRAINT question_set_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.interview_question
    ADD CONSTRAINT fkds48bowpedbgkbxfljxnjkk0f FOREIGN KEY (question_set_id) REFERENCES public.question_set(id);

