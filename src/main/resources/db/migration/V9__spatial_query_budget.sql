alter table ouf_udp.query_budget
  add column spatial_radius_meters double precision not null default 0,
  add column spatial_area_sq_km double precision not null default 0;

create index urban_geometry_geography_gist_idx on ouf_udp.urban_geometry using gist((geometry::geography));
