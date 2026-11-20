#!/bin/sh
set -eu
umask 077

cd "$(dirname "$0")/.."
directory=.local/https
mkdir -p "$directory"

names='DNS:localhost,IP:127.0.0.1'
for hostname in "$@"; do
    label=${hostname%.localhost}
    if [ "$hostname" = "$label" ] || [ "${#label}" -gt 63 ] ||
       ! printf '%s\n' "$label" | grep -Eq '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'; then
        printf '%s\n' 'Use explicit store hostnames such as my-store.localhost.' >&2
        exit 1
    fi
    names="$names,DNS:$hostname"
done

if [ -e "$directory/ca.key" ] || [ -e "$directory/ca.crt" ] ||
   [ -e "$directory/server.key" ] || [ -e "$directory/server.crt" ]; then
    if [ -s "$directory/ca.key" ] && [ -s "$directory/ca.crt" ] &&
       [ -s "$directory/server.key" ] && [ -s "$directory/server.crt" ]; then
        openssl verify -CAfile "$directory/ca.crt" "$directory/server.crt"
        openssl x509 -in "$directory/server.crt" -checkend 86400 -noout
        renew=false
        for hostname in "$@"; do
            if ! openssl x509 -in "$directory/server.crt" -noout -ext subjectAltName |
                tr ',' '\n' | sed 's/^[[:space:]]*//' | grep -Fxq "DNS:$hostname"; then
                renew=true
            fi
        done
        if [ "$renew" = false ]; then
            printf '%s\n' 'Existing local certificates retained.'
            exit 0
        fi
    else
        printf '%s\n' 'Incomplete local certificates. Inspect .local/https before retrying.' >&2
        exit 1
    fi
fi

if [ ! -e "$directory/ca.crt" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 \
        -keyout "$directory/ca.key" -out "$directory/ca.crt" \
        -subj '/CN=Commerce Platform Local Development CA' \
        -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
        -addext 'keyUsage=critical,keyCertSign,cRLSign'
fi

openssl req -new -newkey rsa:2048 -nodes -sha256 \
    -keyout "$directory/server.key" -out "$directory/server.csr" \
    -subj '/CN=localhost' \
    -addext "subjectAltName=$names" \
    -addext 'basicConstraints=critical,CA:FALSE' \
    -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
    -addext 'extendedKeyUsage=serverAuth'

openssl x509 -req -in "$directory/server.csr" \
    -CA "$directory/ca.crt" -CAkey "$directory/ca.key" -CAcreateserial \
    -out "$directory/server.crt" -days 365 -sha256 -copy_extensions copy

openssl verify -CAfile "$directory/ca.crt" "$directory/server.crt"
printf '%s\n' 'Local certificates generated. Trust ca.crt on your machine before browsing HTTPS.'
