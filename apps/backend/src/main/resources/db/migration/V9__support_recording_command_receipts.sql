ALTER TABLE reception_command_receipts
    DROP CONSTRAINT reception_command_receipts_operation_check;
ALTER TABLE reception_command_receipts
    ADD CONSTRAINT reception_command_receipts_operation_check
    CHECK (operation IN ('CREATE', 'CONFIRM', 'REOPEN', 'COMPLETE_RECORDING', 'RESERVE_RECORDING', 'RENEW_RECORDING_UPLOAD'));
