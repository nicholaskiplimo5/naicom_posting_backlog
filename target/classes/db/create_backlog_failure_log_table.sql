-- Run this once against the TQ_EXC schema before starting the app.
-- New table this app owns: records every failure encountered while processing a
-- backlog batch (whichever of SELECT / STAGE / POST it failed in), so failed
-- batches can be inspected and retried without digging through log files.

CREATE TABLE TQ_EXC.TEX_BACKLOG_FAILURE_LOG
(
    BFL_ID            NUMBER(22)     NOT NULL,
    BFL_POL_BATCH_NO  NUMBER(22)     NOT NULL,
    BFL_TRANS_TYPE    VARCHAR2(10)   DEFAULT NULL,
    BFL_STAGE         VARCHAR2(20)   NOT NULL,
    BFL_ERROR_MESSAGE VARCHAR2(4000) DEFAULT NULL,
    BFL_STACK_TRACE   CLOB,
    BFL_PAYLOAD       CLOB,
    BFL_CREATED_DATE  DATE           DEFAULT SYSDATE
);

ALTER TABLE TQ_EXC.TEX_BACKLOG_FAILURE_LOG
    ADD CONSTRAINT BFL_ID_PK PRIMARY KEY (BFL_ID);

CREATE SEQUENCE TQ_EXC.TEX_BACKLOG_FAILURE_LOG_SEQ
    START WITH 1 INCREMENT BY 1 NOCACHE;
