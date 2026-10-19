CREATE TABLE public.analysis_suggestions (
    id uuid NOT NULL,
    jd_requirement text NOT NULL,
    suggestion text NOT NULL,
    analysis_id uuid NOT NULL
);

CREATE TABLE public.compatibility_analyses (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    experience_match integer,
    jd_id uuid NOT NULL,
    keyword_coverage integer,
    owner_email character varying(255) NOT NULL,
    resume_id uuid NOT NULL,
    semantic_similarity integer,
    skills_overlap integer,
    status character varying(20) NOT NULL,
    failure_reason character varying(255),
    completed_at timestamp(6) with time zone
);

ALTER TABLE ONLY public.analysis_suggestions
    ADD CONSTRAINT analysis_suggestions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.compatibility_analyses
    ADD CONSTRAINT compatibility_analyses_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.analysis_suggestions
    ADD CONSTRAINT fkgfmobbckrjyuwnbtdoxao1nmc FOREIGN KEY (analysis_id) REFERENCES public.compatibility_analyses(id);

