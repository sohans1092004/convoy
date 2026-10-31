output "public_ip" { value = oci_core_instance.lite.public_ip }
output "shape" { value = "${oci_core_instance.lite.shape} ${oci_core_instance.lite.shape_config[0].ocpus} OCPU / ${oci_core_instance.lite.shape_config[0].memory_in_gbs} GB" }
output "ssh" { value = "ssh ubuntu@${oci_core_instance.lite.public_ip}" }

output "backup_par_url" {
  description = "BACKUP_PAR_URL in /opt/convoy/convoy.env (a bearer secret: anyone with it can read the encrypted backups)."
  value       = "https://objectstorage.${var.region}.oraclecloud.com${oci_objectstorage_preauthrequest.backups.access_uri}"
  sensitive   = true
}
