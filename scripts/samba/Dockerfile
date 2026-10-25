FROM alpine:3.22@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f
RUN --mount=type=secret,id=proxy_ca,target=/etc/ssl/cert.pem apk add --no-cache samba-server samba-common-tools openjdk17-jre-headless
RUN adduser -D smbtest && printf 'test-only-password\ntest-only-password\n' | smbpasswd -a -s smbtest && mkdir /share && chown smbtest /share
COPY smb.conf /etc/samba/smb.conf
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
