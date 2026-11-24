-- Back the dashboard's same-work history match without joining duplicate request rows.
CREATE INDEX ix_work_orders_request_driver_status ON work_orders(request_id, driver_id, status);
