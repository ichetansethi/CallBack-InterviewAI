
CREATE TABLE public.job_descriptions (
    id uuid NOT NULL,
    company character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    owner_email character varying(255) NOT NULL,
    raw_text text NOT NULL,
    role character varying(255) NOT NULL
);

CREATE TABLE public.resumes (
    id uuid NOT NULL,
    content_type character varying(255) NOT NULL,
    file_size_bytes bigint NOT NULL,
    original_filename character varying(255) NOT NULL,
    owner_email character varying(255) NOT NULL,
    storage_key character varying(255) NOT NULL,
    uploaded_at timestamp(6) with time zone NOT NULL,
    extracted_text text
);

ALTER TABLE ONLY public.job_descriptions
    ADD CONSTRAINT job_descriptions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.resumes
    ADD CONSTRAINT resumes_pkey PRIMARY KEY (id);

