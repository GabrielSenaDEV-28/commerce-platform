ALTER TABLE customer_accounts
    ADD COLUMN cpf VARCHAR(11) CHECK (cpf ~ '^[0-9]{11}$'),
    ADD CONSTRAINT uk_customer_store_cpf UNIQUE (store_id, cpf);

ALTER TABLE commerce_orders
    ADD COLUMN customer_cpf VARCHAR(11) CHECK (customer_cpf ~ '^[0-9]{11}$');
