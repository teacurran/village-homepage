-- // create_changelog_table
-- Creates the changelog metadata table that MyBatis Migrations uses to track applied scripts.

CREATE TABLE ${changelog} (
    id        NUMERIC(20, 0) NOT NULL,
    applied_at VARCHAR(25)   NOT NULL,
    description VARCHAR(255) NOT NULL
);

ALTER TABLE ${changelog}
    ADD CONSTRAINT pk_${changelog}
    PRIMARY KEY (id);

-- //@UNDO

DROP TABLE ${changelog};
