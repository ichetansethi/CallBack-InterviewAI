
CREATE TABLE public.users (
    created_at timestamp(6) with time zone NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL
);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

