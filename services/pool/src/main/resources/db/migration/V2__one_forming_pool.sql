-- P2 step 0.5: at most one FORMING pool per (model, city, purchase month); concurrent creates converge on it.
create unique index one_forming_pool on pools (model_slug, city, purchase_month) where state = 'FORMING';
