resource "oci_core_vcn" "v" {
  compartment_id = local.compartment
  display_name   = "convoy"
  cidr_blocks    = ["10.42.0.0/16"]
  dns_label      = "convoy"
  freeform_tags  = local.tags
}

resource "oci_core_internet_gateway" "igw" {
  compartment_id = local.compartment
  vcn_id         = oci_core_vcn.v.id
  display_name   = "convoy-igw"
  freeform_tags  = local.tags
}

resource "oci_core_route_table" "public" {
  compartment_id = local.compartment
  vcn_id         = oci_core_vcn.v.id
  display_name   = "convoy-public"
  freeform_tags  = local.tags
  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.igw.id
  }
}

# Only 22 (from admin_cidr), 80 and 443 in. The VM's own iptables opens the same (bootstrap.sh): Ubuntu images on OCI
# ship with a REJECT rule, so both layers must agree.
resource "oci_core_security_list" "public" {
  compartment_id = local.compartment
  vcn_id         = oci_core_vcn.v.id
  display_name   = "convoy-public"
  freeform_tags  = local.tags

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }

  ingress_security_rules {
    source   = var.admin_cidr
    protocol = "6"
    tcp_options {
      min = 22
      max = 22
    }
  }

  dynamic "ingress_security_rules" {
    for_each = [80, 443]
    content {
      source   = "0.0.0.0/0"
      protocol = "6"
      tcp_options {
        min = ingress_security_rules.value
        max = ingress_security_rules.value
      }
    }
  }

  # Path MTU discovery (ICMP type 3 code 4), as in OCI's default list
  ingress_security_rules {
    source   = "0.0.0.0/0"
    protocol = "1"
    icmp_options {
      type = 3
      code = 4
    }
  }
}

resource "oci_core_subnet" "public" {
  compartment_id    = local.compartment
  vcn_id            = oci_core_vcn.v.id
  display_name      = "convoy-public"
  cidr_block        = "10.42.1.0/24"
  dns_label         = "pub"
  route_table_id    = oci_core_route_table.public.id
  security_list_ids = [oci_core_security_list.public.id]
  freeform_tags     = local.tags
}

resource "oci_core_instance" "lite" {
  compartment_id      = local.compartment
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[var.availability_domain_index].name
  display_name        = "convoy-lite"
  shape               = "VM.Standard.A1.Flex"
  freeform_tags       = local.tags

  shape_config {
    ocpus         = 1
    memory_in_gbs = 6
  }

  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu.images[0].id
    boot_volume_size_in_gbs = 50
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
    hostname_label   = "convoy"
  }

  metadata = {
    ssh_authorized_keys = file(pathexpand(var.ssh_public_key_path))
  }

  lifecycle {
    ignore_changes = [source_details[0].source_id] # a newer Ubuntu image must not replace the running VM
  }
}
