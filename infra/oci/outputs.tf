output "public_ip" { value = oci_core_instance.lite.public_ip }
output "shape" { value = "${oci_core_instance.lite.shape} ${oci_core_instance.lite.shape_config[0].ocpus} OCPU / ${oci_core_instance.lite.shape_config[0].memory_in_gbs} GB" }
output "ssh" { value = "ssh ubuntu@${oci_core_instance.lite.public_ip}" }
output "ad_count" { value = length(data.oci_identity_availability_domains.ads.availability_domains) }
output "namespace" { value = data.oci_objectstorage_namespace.ns.namespace }
output "a1_usage" { value = "other A1 in the tenancy: ${local.a1_other_ocpus} OCPU / ${local.a1_other_gb} GB; with convoy-lite: ${local.a1_other_ocpus + var.ocpus} / ${local.a1_other_gb + var.memory_gb} (Always Free 2 / 12)" }
