variable "tenancy_ocid" {
  type = string
}

variable "compartment_ocid" {
  description = "Empty: the root compartment (the tenancy)."
  type        = string
  default     = ""
}

variable "region" {
  description = "Your HOME region: Always Free A1 capacity exists only there. On 'Out of host capacity', retry (retry-apply.sh); never change region or shape."
  type        = string
}

variable "oci_profile" {
  type    = string
  default = "DEFAULT"
}

variable "ssh_public_key_path" {
  type    = string
  default = "~/.ssh/id_ed25519.pub"
}

variable "admin_cidr" {
  description = "Your public IP as a /32 (e.g. 203.0.113.7/32): the only source allowed to SSH."
  type        = string
  validation {
    condition     = can(cidrhost(var.admin_cidr, 0)) && endswith(var.admin_cidr, "/32")
    error_message = "admin_cidr must be one address as a /32."
  }
}

variable "availability_domain_index" {
  description = "Which AD of the home region to try (retry-apply.sh cycles through them on 'Out of host capacity')."
  type        = number
  default     = 0
}
