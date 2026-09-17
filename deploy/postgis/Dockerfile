FROM postgis/postgis:17-3.5-alpine
RUN apk add --no-cache python3
ENV PROJ_NETWORK=OFF
COPY verify-grids.py /usr/local/bin/ouf-verify-grids.py
COPY entrypoint.sh /usr/local/bin/ouf-postgis-entrypoint.sh
ENTRYPOINT ["/bin/sh", "/usr/local/bin/ouf-postgis-entrypoint.sh"]
CMD ["postgres"]
